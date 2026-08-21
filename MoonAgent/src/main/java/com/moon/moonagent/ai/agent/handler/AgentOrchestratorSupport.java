package com.moon.moonagent.ai.agent.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.ai.agent.AssetRelevanceResult;
import com.moon.moonagent.ai.GatewayModelService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图处理器共享支持组件（组合优于继承：避免手写构造器，per-request 状态由
 * {@link OrchestrationRequest#lastMessage} 承担，组件本身无状态、并发安全）。
 *
 * <p>职责：SSE 事件发送、HITL checkpoint 落库/解析、剧本优化/分镜方案/图片提示词/视频方案
 * 等 LLM 调用（复用网关默认文本模型（动态获取），超时 120s）、HITL 通用模板
 * {@link #runHITLStage}。原 AgentOrchestratorImpl 中对应私有方法整体迁入。
 */
@Component
@RequiredArgsConstructor
public class AgentOrchestratorSupport {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorSupport.class);

    /** checkpoint 过期时间（对齐原 FORM_SNAPSHOT_TTL_MS：30 分钟） */
    public static final Duration CHECKPOINT_TTL = Duration.ofMinutes(30);

    /** checkpoint step 常量（原 Step 枚举 EXECUTE 阶段；跨包用字符串常量避免枚举暴露） */
    public static final String STEP_EXECUTE = "EXECUTE";

    private final AgentCheckpointMapper checkpointMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final com.moon.moonagent.ai.AgentAiConfigProperties agentConfig;
    private final AgentGenerationService generationService;
    private final AgentAssetMapper assetMapper;
    private final GatewayModelService gatewayModelService;
    private final StoryboardClient storyboardClient;
    private final com.moon.moonagent.ai.agent.AssetMatchingService assetMatchingService;
    private final com.moon.moonagent.mapper.AgentMessageMapper messageMapper;
    private final com.moon.moonagent.config.PromptConfig promptConfig;

    /** 视频异步后台轮询专用 executor（虚拟线程，不占池） */
    private final ExecutorService agentExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 剧本优化 / 分镜方案 LLM（懒加载，复用网关默认对话模型，超时 120s） */
    private volatile ChatClient planClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 澄清计数：conversationId → 连续追问次数（跨用户消息持续）。
     * 每轮 gate（剧本优化/分镜方案）type=0 时 +1；type=1（有进展）或非 aisplit 轮清零。
     * 达到 {@code maxClarifyRounds} 上限后不再追问，直接给默认方案让用户选。
     * ponytail: 内存态重启即失（计数清零，用户重新获得追问额度，无害）；多实例需落 DB 列。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> clarifyCount =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 澄清上限判定：本次 type=0 记 +1；未达上限返回 false（调用方正常追问并结束本轮）；
     * 已达上限返回 true（调用方跳过追问，以用户原始需求为默认方案继续）。
     */
    public boolean clarifyLimitReached(String conversationId, OrchestrationRequest req) {
        int n = clarifyCount.merge(conversationId, 1, Integer::sum);
        if (n > agentConfig.getMaxClarifyRounds()) {
            clarifyCount.remove(conversationId);
            sendMessage(req, "已按你的原始需求直接生成方案，可在确认卡片上调整。");
            return true;
        }
        return false;
    }

    /** 澄清计数清零（type=1 有进展 / 非 aisplit 轮调用） */
    public void resetClarify(String conversationId) {
        clarifyCount.remove(conversationId);
    }

    /** 会话删除时清理全部内存态（clarifyCount） */
    public void cleanupOnDelete(String conversationId) {
        clarifyCount.remove(conversationId);
        regenCount.remove(conversationId);
        sessionAssets.remove(conversationId);
    }

    // ===== P4（2026-08-21）：regenCount/sessionAssets 由 AisplitIntentHandler 迁入统一管理，
    // 随 checkpoint.plan JSON 持久化（_regenCount/_clarifyCount/_sessionAssets 私有键），resume 时恢复 =====

    /** 连续不满意调整计数（写库成功/达上限清零） */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> regenCount =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 本轮勾选资产：conversationId → assetIds（资产选择卡片确认后写入，HITL 链内接力） */
    private final java.util.concurrent.ConcurrentHashMap<String, List<String>> sessionAssets =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 不满意调整 +1，返回新值 */
    public int incrRegenCount(String conversationId) {
        return regenCount.merge(conversationId, 1, Integer::sum);
    }

    /** 写库成功/达上限清零 */
    public void clearRegenCount(String conversationId) {
        regenCount.remove(conversationId);
    }

    /** 记录本轮勾选资产 */
    public void setSessionAssets(String conversationId, List<String> ids) {
        sessionAssets.put(conversationId, ids);
    }

    /** 本轮已确认资产 ID（无记录返回空列表） */
    public List<String> getSessionAssets(String conversationId) {
        return sessionAssets.getOrDefault(conversationId, List.of());
    }

    /** 本轮是否已选过资产（防 handle 重入重复弹资产卡片） */
    public boolean hasSessionAssets(String conversationId) {
        return sessionAssets.containsKey(conversationId);
    }

    /** 本轮结束清理 */
    public void clearSessionAssets(String conversationId) {
        sessionAssets.remove(conversationId);
    }

    /** 计数写入 checkpoint.plan（createCheckpoint 内调用）——重启/刷新后 resume 可恢复 */
    void persistCountersToPlan(Map<String, Object> plan, String conversationId) {
        plan.put("_regenCount", regenCount.getOrDefault(conversationId, 0));
        plan.put("_clarifyCount", clarifyCount.getOrDefault(conversationId, 0));
        plan.put("_sessionAssets", sessionAssets.getOrDefault(conversationId, List.of()));
    }

    /** 从 checkpoint.plan 恢复计数（resume 消费后调用）——重启后内存计数不丢 */
    public void restoreCountersFromPlan(String conversationId, String planJson) {
        try {
            if (planJson == null || planJson.isBlank()) return;
            Object root = objectMapper.readValue(planJson, Object.class);
            if (!(root instanceof Map<?, ?> m)) return;
            Object rc = m.get("_regenCount");
            if (rc instanceof Number n) regenCount.put(conversationId, n.intValue());
            Object cc = m.get("_clarifyCount");
            if (cc instanceof Number n) clarifyCount.put(conversationId, n.intValue());
            Object sa = m.get("_sessionAssets");
            if (sa instanceof List<?> list) {
                sessionAssets.put(conversationId, list.stream()
                        .filter(String.class::isInstance).map(String.class::cast).toList());
            }
        } catch (Exception e) {
            log.debug("restoreCountersFromPlan 解析失败（无害）: {}", e.getMessage());
        }
    }

    // ===== 结构化输出 record（原 AgentOrchestratorImpl 内部 record 迁入） =====

    /** 剧本优化结构化输出（options：type=0 追问时的 2~4 个选项；type=1 时为空/缺失） */
    public record ScriptOptimizeResult(int type, String message, String script,
                                       List<Map<String, Object>> options) {}
    /** 分镜方案结构化输出（options：同上） */
    public record StoryboardPlanResult(int type, String message,
                                       List<Map<String, Object>> options) {}
    /** 无图视频方案结构化输出（params=LLM 推荐的生成参数，reasons=推荐理由；可空） */
    public record VideoPlanResult(String message, int duration,
                                  Map<String, String> params, Map<String, String> reasons) {
        /** 兼容构造器：无推荐参数（解析失败兜底/现状调用） */
        public VideoPlanResult(String message, int duration) {
            this(message, duration, Map.of(), Map.of());
        }
    }

    /** 图片修改方向选项（refine 继续完善时 LLM 按当前方案动态生成；options=[{id,title}]） */
    public record PicRefineOptionsResult(String message, List<Map<String, Object>> options) {}

    /** 无参考图文生图方案结构化输出（params=LLM 推荐的生成参数，reasons=推荐理由） */
    public record ImagePlanResult(String prompt, Map<String, String> params, Map<String, String> reasons) {
        /** 兼容构造器：无推荐参数 */
        public ImagePlanResult(String prompt) {
            this(prompt, Map.of(), Map.of());
        }
    }

    /** 图片需求澄清结构化输出（无图链：type=1 需求明确可出方案；type=0 需追问，options 供选项卡片） */
    public record ImageClarifyResult(int type, String message, List<Map<String, Object>> options) {}

    /** 图片生成失败自救结果：recoverable=审核拒绝可重写提示词；rewrittenPrompt=重写后的提示词；message=给用户的说明 */
    public record ImageRecoveryResult(boolean recoverable, String rewrittenPrompt, String message) {}

    /**
     * HITL 阶段产出（模板 {@link #runHITLStage} 的输入）：
     * 方案文本 + checkpoint action + plan 载荷 + 事件名（human_input / video_plan）+ 确认按钮
     * + models（网关模型列表含参数能力，卡片参数选择器用）+ recommended/reasons（LLM 推荐参数值与理由）
     * + imageModels/videoModels（aisplit 分镜确认卡片分图片/视频两组模型列表；空=不渲染分区选择器）。
     */
    public record StagePlan(String planText, String action, List<Map<String, Object>> planPayload,
                            String eventName, List<Map<String, Object>> actions,
                            List<Map<String, Object>> models,
                            Map<String, String> recommended,
                            Map<String, String> reasons,
                            List<Map<String, Object>> imageModels,
                            List<Map<String, Object>> videoModels,
                            List<Map<String, Object>> assets) {
        /** 兼容构造器：无模型/推荐参数/资产（现有调用零改动） */
        public StagePlan(String planText, String action, List<Map<String, Object>> planPayload,
                         String eventName, List<Map<String, Object>> actions) {
            this(planText, action, planPayload, eventName, actions, List.of(), Map.of(), Map.of(), List.of(), List.of(), null);
        }

        /** 兼容构造器：单组模型/推荐参数（pic/video 链路现有调用，无分区模型列表/资产） */
        public StagePlan(String planText, String action, List<Map<String, Object>> planPayload,
                         String eventName, List<Map<String, Object>> actions,
                         List<Map<String, Object>> models,
                         Map<String, String> recommended,
                         Map<String, String> reasons) {
            this(planText, action, planPayload, eventName, actions, models, recommended, reasons, List.of(), List.of(), null);
        }

        /** 兼容构造器：分区模型列表（aisplit 分镜确认卡片现有调用，无资产） */
        public StagePlan(String planText, String action, List<Map<String, Object>> planPayload,
                         String eventName, List<Map<String, Object>> actions,
                         List<Map<String, Object>> models,
                         Map<String, String> recommended,
                         Map<String, String> reasons,
                         List<Map<String, Object>> imageModels,
                         List<Map<String, Object>> videoModels) {
            this(planText, action, planPayload, eventName, actions, models, recommended, reasons, imageModels, videoModels, null);
        }
    }

    /** aisplit 分镜参数推荐结果：图片/视频两组模型列表 + LLM 推荐参数（平铺前缀键）+ 推荐理由 */
    public record SceneParamsRecommendation(List<Map<String, Object>> imageModels,
                                            List<Map<String, Object>> videoModels,
                                            Map<String, String> recommended,
                                            Map<String, String> reasons) {
        public static SceneParamsRecommendation empty() {
            return new SceneParamsRecommendation(List.of(), List.of(), Map.of(), Map.of());
        }
    }

    /** LLM 推荐参数输出（嵌套结构，BeanOutputConverter 直接解析） */
    public record RecommendedParams(ImageRec image, VideoRec video, Map<String, String> reasons) {
        public record ImageRec(String model, String size, String quality) {}
        public record VideoRec(String model, String duration, String resolution, String aspectRatio) {}
    }

    // ===== LLM 调用（结构化输出：纯解析，不发 response_format） =====

    /** 剧本优化：手动澄清循环的单步调用（message 字段流式增量转发） */
    public ScriptOptimizeResult callScriptOptimize(String content, OrchestrationRequest req) {
        try {
            String raw = streamPlanWithMessage(
                    promptConfig.get("orchestrator/script-optimize"),
                    content, req);
            return new BeanOutputConverter<>(ScriptOptimizeResult.class).convert(raw);
        } catch (Exception e) {
            log.warn("剧本优化 LLM 调用失败: {}", e.getMessage());
            return new ScriptOptimizeResult(0, "已理解你的需求，请继续补充。", null, List.of());
        }
    }

    /**
     * 需求澄清 gate：分析用户分镜需求的模糊程度，输出多维度澄清问题。
     * 复用 ScriptOptimizeResult 结构（type=0 需澄清 / type=1 已明确直接跳过）。
     */
    public ScriptOptimizeResult callRequirementClarify(String content, OrchestrationRequest req) {
        try {
            String raw = streamPlanWithMessage(
                    promptConfig.get("orchestrator/requirement-clarify"),
                    content, req);
            return new BeanOutputConverter<>(ScriptOptimizeResult.class).convert(raw);
        } catch (Exception e) {
            log.warn("需求澄清 LLM 调用失败（降级跳过）: {}", e.getMessage());
            // 失败直接跳过澄清，不阻塞主流程
            return new ScriptOptimizeResult(1, "", null, List.of());
        }
    }

    /** 分镜方案设计（message 字段流式增量转发） */
    public StoryboardPlanResult callStoryboardPlan(String script, OrchestrationRequest req) {
        try {
            String raw = streamPlanWithMessage(
                    promptConfig.get("orchestrator/storyboard-plan"),
                    script, req);
            return new BeanOutputConverter<>(StoryboardPlanResult.class).convert(raw);
        } catch (Exception e) {
            log.warn("分镜方案 LLM 调用失败: {}", e.getMessage());
            return new StoryboardPlanResult(1, "已根据剧本生成方案。", List.of());
        }
    }

    /**
     * 流式调用 planClient：逐 token 收集完整响应，同时提取 JSON 中 {@code message} 字段值
     * 增量转发为 SSE message 事件（打字机效果；结构化输出本身仍需完整 JSON 解析，故不转发原始 token）。
     *
     * @return 完整响应文本（调用方 BeanOutputConverter 解析）
     */
    public String streamPlanWithMessage(String systemPrompt, String userContent, OrchestrationRequest req) {
        StringBuilder full = new StringBuilder();
        // 已转发的 message 字段值（增量对比基准）
        AtomicReference<String> emitted = new AtomicReference<>("");
        try {
            planClient().prompt()
                .system(systemPrompt)
                .user(userContent)
                .stream()
                .content()
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.isBlank()) return;
                    full.append(chunk);
                    String msg = extractMessageField(full.toString());
                    if (msg != null && msg.length() > emitted.get().length()) {
                        String delta = msg.substring(emitted.get().length());
                        emitted.set(msg);
                        sendEvent(req, "message", Map.of("content", delta));
                    }
                })
                .blockLast();
        } catch (Exception e) {
            log.warn("流式 LLM 调用失败: {}", e.getMessage());
        }
        return full.toString();
    }

    /** 宽松提取 JSON 字符串中的 message 字段值（未闭合/截断时返回已见部分；失败返回 null） */
    public String extractMessageField(String json) {
        if (json == null) return null;
        // 兼容 "message":"..." 与 "message": "..."（LLM 输出常带空格）
        Matcher m = Pattern.compile("\"message\"\\s*:\\s*\"").matcher(json);
        if (!m.find()) return null;
        int start = m.end();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    /** 无参考图文生图：LLM 生成图片提示词（瞬态失败重试 1 次；强制中文输出——方案卡片直接展示给用户） */
    public String callImagePrompt(String content) {
        try {
            String raw = retryTransient(() -> planClient().prompt()
                .system(promptConfig.get("orchestrator/image-prompt"))
                .user(content)
                .call()
                .content());
            return raw != null && !raw.isBlank() ? raw.trim() : content;
        } catch (Exception e) {
            log.warn("图片提示词 LLM 调用失败: {}", e.getMessage());
            return content;
        }
    }

    /**
     * 带参数推荐的图片方案生成：LLM 同时输出提示词 + 推荐生成参数（模型/尺寸/质量）。
     * 复用 callVideoPlan 的结构化输出模式，返回 ImagePlanResult。
     */
    public ImagePlanResult callImagePromptWithParams(String content, String modelOptionsText) {
        boolean withParams = modelOptionsText != null && !modelOptionsText.isBlank();
        String systemPrompt = promptConfig.get("orchestrator/image-prompt-with-params",
                Map.of("modelOptionsText", withParams ? modelOptionsText : ""));
        try {
            String raw = retryTransient(() -> planClient().prompt()
                    .system(systemPrompt)
                    .user(content)
                    .call()
                    .content());
            // 解析 JSON：prompt 必须有，params/reasons 可选（LLM 未按格式输出时兜底空 Map）
            ImagePlanResult result = new BeanOutputConverter<>(ImagePlanResult.class).convert(raw);
            if (result == null || result.prompt() == null || result.prompt().isBlank()) {
                // 解析失败：退化为纯文本提示词（与 callImagePrompt 行为一致）
                String fallback = callImagePrompt(content);
                return new ImagePlanResult(fallback);
            }
            return new ImagePlanResult(result.prompt(),
                    result.params() == null ? Map.of() : result.params(),
                    result.reasons() == null ? Map.of() : result.reasons());
        } catch (Exception e) {
            log.warn("图片方案 LLM 调用失败，退化为纯提示词: {}", e.getMessage());
            String fallback = callImagePrompt(content);
            return new ImagePlanResult(fallback);
        }
    }

    /**
     * 图片需求澄清（无图链 gate）：LLM 判断用户描述是否足以生成明确图片。
     * type=1 需求明确（可继续出方案）；type=0 关键信息缺失需追问（message 只问一个最关键的问题，options 2~4 个）。
     * 用户回复只要提供了任何有效信息就直接生成方案；仅回复为空或与图片生成完全无关才 type=0。
     * LLM 调用失败 → type=1 放行（不阻塞出方案）。
     */
    public ImageClarifyResult callImageClarify(String content) {
        try {
            String raw = retryTransient(() -> planClient().prompt()
                .system(promptConfig.get("orchestrator/image-clarify"))
                .user(content)
                .call()
                .content());
            ImageClarifyResult r = new BeanOutputConverter<>(ImageClarifyResult.class).convert(raw);
            if (r == null) return new ImageClarifyResult(1, "", List.of());
            return new ImageClarifyResult(r.type(),
                    r.message() == null ? "" : r.message(),
                    r.options() == null ? List.of() : r.options());
        } catch (Exception e) {
            log.warn("图片需求澄清 LLM 调用失败，按明确放行: {}", e.getMessage());
            return new ImageClarifyResult(1, "", List.of());
        }
    }

    /**
     * 视频需求澄清 gate：分析用户视频需求的模糊程度，多维度追问。
     * 复用 ImageClarifyResult 结构（type=0 需澄清 / type=1 已明确）。
     */
    public ImageClarifyResult callVideoClarify(String content, OrchestrationRequest req) {
        try {
            String raw = streamPlanWithMessage(
                    promptConfig.get("orchestrator/video-clarify"),
                    content, req);
            return new BeanOutputConverter<>(ImageClarifyResult.class).convert(raw);
        } catch (Exception e) {
            log.warn("视频需求澄清 LLM 调用失败（降级跳过）: {}", e.getMessage());
            return new ImageClarifyResult(1, "", List.of());
        }
    }

    /** 无参考图视频：LLM 生成视频方案（无模型选项，兼容现状调用） */
    public VideoPlanResult callVideoPlan(String content) {
        return callVideoPlan(content, null);
    }

    /**
     * 图片修改方向选项（refine「继续完善」时调用）：基于当前图片方案，
     * LLM 从不同维度（场景/服装/氛围/画风等，按方案内容动态）给出 2~4 个修改方向选项。
     * 解析失败/空输出 → 兜底通用 4 方向（保证卡片始终有选项可点）。
     */
    public PicRefineOptionsResult callPicRefineOptions(String basePrompt, boolean hasSource) {
        try {
            String raw = retryTransient(() -> planClient().prompt()
                .system(promptConfig.get("orchestrator/pic-refine-options",
                    Map.of("hasSourceNote", hasSource ? promptConfig.get("orchestrator/pic-refine-options-source-note") : "")))
                .user("当前图片方案：" + basePrompt)
                .call()
                .content());
            PicRefineOptionsResult result = new BeanOutputConverter<>(PicRefineOptionsResult.class).convert(raw);
            if (result.options() == null || result.options().isEmpty()) {
                return fallbackPicRefineOptions();
            }
            return result;
        } catch (Exception e) {
            log.warn("图片修改选项 LLM 调用失败: {}", e.getMessage());
            return fallbackPicRefineOptions();
        }
    }

    /** 兜底选项：LLM 失败/空输出时保证卡片可点 */
    private PicRefineOptionsResult fallbackPicRefineOptions() {
        return new PicRefineOptionsResult("想怎么调整这张图片？", List.of(
                Map.of("id", "opt1", "title", "场景-换个环境"),
                Map.of("id", "opt2", "title", "氛围-更突出"),
                Map.of("id", "opt3", "title", "画风-更精致"),
                Map.of("id", "opt4", "title", "构图-重新布局")));
    }

    /**
     * 无参考图视频：LLM 生成视频方案（prompt + 时长 + 推荐参数；瞬态失败重试 1 次）。
     *
     * @param modelOptionsText 可选的模型与参数枚举文本（来自 {@link #buildModelOptionsText}；空=不要求 LLM 选参）
     */
    public VideoPlanResult callVideoPlan(String content, String modelOptionsText) {
        try {
            boolean withParams = modelOptionsText != null && !modelOptionsText.isBlank();
            String system = promptConfig.get("orchestrator/video-plan",
                    Map.of("modelOptionsText", withParams ? modelOptionsText : ""));
            String raw = retryTransient(() -> planClient().prompt()
                .system(system)
                .user(content)
                .call()
                .content());
            VideoPlanResult plan = new BeanOutputConverter<>(VideoPlanResult.class).convert(raw);
            // 兜底：params/reasons 缺失（LLM 未按要求输出）→ 空 Map，行为与现状一致
            return new VideoPlanResult(plan.message(), plan.duration(),
                    plan.params() == null ? Map.of() : plan.params(),
                    plan.reasons() == null ? Map.of() : plan.reasons());
        } catch (Exception e) {
            log.warn("视频方案 LLM 调用失败: {}", e.getMessage());
            return new VideoPlanResult(content, 8);
        }
    }

    /**
     * 瞬态失败重试 1 次（LLM 调用幂等可安全重试；500ms backoff）。
     * 非瞬态异常（解析/校验类）直接抛，由调用方兜底。仅用于非流式 call()——
     * 流式 streamPlanWithMessage 不重试（会重复转发 message 增量）。
     */
    private static <T> T retryTransient(java.util.function.Supplier<T> fn) {
        try {
            return fn.get();
        } catch (RuntimeException e) {
            if (!isTransient(e)) throw e;
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return fn.get();
        }
    }

    /** 瞬态判定：HTTP 429/5xx（Spring 状态码异常）或网络超时/连接失败 */
    private static boolean isTransient(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof org.springframework.web.client.HttpStatusCodeException sce) {
                int s = sce.getStatusCode().value();
                if (s == 429 || s >= 500) return true;
            }
            if (c instanceof java.net.ConnectException
                    || c instanceof java.net.http.HttpTimeoutException
                    || c instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 懒加载 planClient：对话交流统一网关默认文本模型（动态获取） */
    private ChatClient planClient() {
        if (planClient == null) {
            synchronized (this) {
                if (planClient == null) {
                    planClient = chatClientBuilder
                        .defaultOptions(OpenAiChatOptions.builder()
                            .model(gatewayModelService.getDefaultTextModel())
                            .timeout(Duration.ofSeconds(300)))
                        .build();
                }
            }
        }
        return planClient;
    }

    // ===== 模型能力选项（网关 fetchModels 透传，卡片/LLM 选参共用） =====

    /** 卡片模型选项：网关 fetchModels(type) → [{value,label,params(JSON字符串)}]（网关不可用返回空列表，前端回退静态兜底） */
    public List<Map<String, Object>> buildModels(String type) {
        try {
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (Map<String, String> m : gatewayModelService.fetchModels(type)) {
                out.add(new java.util.LinkedHashMap<>(m));
            }
            return out;
        } catch (Exception e) {
            log.warn("网关模型列表获取失败: type={}, error={}", type, e.getMessage());
            return List.of();
        }
    }

    /** LLM 选参用的人类可读选项文本：模型名 + 参数枚举（来自网关 params JSON 字符串；无选项返回空串） */
    public String buildModelOptionsText(String type) {
        List<Map<String, Object>> models = buildModels(type);
        if (models.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : models) {
            sb.append("- ").append(m.get("value"));
            String paramsJson = m.get("params") instanceof String s ? s : null;
            if (paramsJson != null && !paramsJson.isBlank()) {
                try {
                    JsonNode p = objectMapper.readTree(paramsJson);
                    List<String> parts = new java.util.ArrayList<>();
                    if (p.has("resolutions")) parts.add("分辨率 " + p.get("resolutions"));
                    if (p.has("durations")) parts.add("时长 " + p.get("durations") + " 秒");
                    if (p.has("aspectRatios")) parts.add("画幅 " + p.get("aspectRatios"));
                    if (p.has("sizes")) parts.add("尺寸 " + p.get("sizes"));
                    if (p.has("qualities")) parts.add("质量 " + p.get("qualities"));
                    if (!parts.isEmpty()) sb.append("（").append(String.join("；", parts)).append("）");
                } catch (Exception ignored) {
                    // params 解析失败忽略，仅保留模型名
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ===== HITL checkpoint =====

    /**
     * aisplit 分镜参数推荐（整套统一一套：图片 + 视频各一套）：LLM 从网关模型选项中选择，
     * 返回两组模型列表 + 平铺推荐参数（image/video 前缀键）+ 推荐理由。
     * 推荐失败：模型列表照常下发（选择器用模型默认值），无推荐理由，分镜流程不受影响。
     */
    public SceneParamsRecommendation recommendSceneParams(String script, OrchestrationRequest req) {
        List<Map<String, Object>> imageModels = buildModels("image");
        List<Map<String, Object>> videoModels = buildModels("video");
        if (imageModels.isEmpty() && videoModels.isEmpty()) return SceneParamsRecommendation.empty();
        try {
            String imageOpts = buildModelOptionsText("image");
            String videoOpts = buildModelOptionsText("video");
            String raw = retryTransient(() -> planClient().prompt()
                .system(promptConfig.get("orchestrator/scene-params-recommend"))
                .user("剧本：\n" + script
                    + "\n\n可选生图模型与参数：\n" + (imageOpts.isBlank() ? "（无）" : imageOpts)
                    + "\n可选视频模型与参数：\n" + (videoOpts.isBlank() ? "（无）" : videoOpts))
                .call()
                .content());
            RecommendedParams r = new BeanOutputConverter<>(RecommendedParams.class).convert(raw);
            if (r == null) return new SceneParamsRecommendation(imageModels, videoModels, Map.of(), Map.of());
            Map<String, String> recommended = new java.util.LinkedHashMap<>();
            Map<String, String> reasons = new java.util.LinkedHashMap<>(r.reasons() == null ? Map.of() : r.reasons());
            if (r.image() != null) {
                if (r.image().model() != null) recommended.put("imageModel", r.image().model());
                if (r.image().size() != null) recommended.put("imageSize", r.image().size());
                if (r.image().quality() != null) recommended.put("imageQuality", r.image().quality());
            }
            if (r.video() != null) {
                if (r.video().model() != null) recommended.put("videoModel", r.video().model());
                if (r.video().duration() != null) recommended.put("videoDuration", r.video().duration());
                if (r.video().resolution() != null) recommended.put("videoResolution", r.video().resolution());
                if (r.video().aspectRatio() != null) recommended.put("videoAspectRatio", r.video().aspectRatio());
            }
            return new SceneParamsRecommendation(imageModels, videoModels, recommended, reasons);
        } catch (Exception e) {
            log.warn("分镜参数推荐 LLM 调用失败: {}", e.getMessage());
            return new SceneParamsRecommendation(imageModels, videoModels, Map.of(), Map.of());
        }
    }

    /** 用户确认的整套推荐参数（选择器默认值=推荐值）→ 应用到项目全部分镜的覆盖参数列（空键跳过） */
    public void applySceneParamsToProject(String projectId, Map<String, String> params) {
        if (projectId == null || params == null || params.isEmpty()) return;
        Map<String, String> nonNull = new java.util.LinkedHashMap<>();
        for (var e : params.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) nonNull.put(e.getKey(), e.getValue().trim());
        }
        // videoDuration 需要转 Integer（服务端 params 列类型）
        if (nonNull.containsKey("videoDuration")) {
            try { Integer.parseInt(nonNull.get("videoDuration")); } catch (NumberFormatException e) { nonNull.remove("videoDuration"); }
        }
        if (nonNull.isEmpty()) return;
        try {
            storyboardClient.updateProjectSceneParams(projectId, nonNull);
        } catch (Exception e) {
            log.warn("应用分镜参数失败: projectId={}, error={}", projectId, e.getMessage());
        }
    }

    /** 创建 HITL checkpoint（pending，30min 过期），返回 form_token。
     * 同时把 actions/formContent/models/assets 等恢复所需字段存进 plan JSON，
     * 供页面刷新后 pending-checkpoint 端点重建 human_input 事件。 */
    public String createCheckpoint(AgentConversation conversation, String action,
                                   List<Map<String, Object>> planPayload, String step,
                                   String formContent, List<Map<String, Object>> actions,
                                   List<Map<String, Object>> models,
                                   Map<String, String> recommended,
                                   Map<String, String> reasons,
                                   List<Map<String, Object>> imageModels,
                                   List<Map<String, Object>> videoModels,
                                   List<Map<String, Object>> assets) {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setConversationId(conversation.getId());
        cp.setAction(action);
        cp.setFormToken(UUID.randomUUID().toString());
        cp.setStep(step);
        cp.setStatus("pending");
        cp.setExpirationTime(OffsetDateTime.now().plus(CHECKPOINT_TTL));
        try {
            Map<String, Object> plan = new java.util.LinkedHashMap<>();
            plan.put("items", planPayload);
            plan.put("_formContent", formContent);
            plan.put("_actions", actions);
            if (models != null && !models.isEmpty()) plan.put("_models", models);
            if (recommended != null && !recommended.isEmpty()) plan.put("_recommended", recommended);
            if (reasons != null && !reasons.isEmpty()) plan.put("_reasons", reasons);
            if (imageModels != null && !imageModels.isEmpty()) plan.put("_imageModels", imageModels);
            if (videoModels != null && !videoModels.isEmpty()) plan.put("_videoModels", videoModels);
            if (assets != null && !assets.isEmpty()) plan.put("_assets", assets);
            // P4：会话级内存计数随 checkpoint 持久化（重启后 resume 恢复上限判定/资产接力）
            persistCountersToPlan(plan, conversation.getId());
            cp.setPlan(objectMapper.writeValueAsString(plan));
        } catch (Exception e) {
            log.warn("checkpoint plan 序列化失败: {}", e.getMessage());
        }
        checkpointMapper.insert(cp);
        log.info("AgentOrchestrator: checkpoint 已落库 conversationId={} formToken={} action={}",
            conversation.getId(), cp.getFormToken(), action);
        return cp.getFormToken();
    }

    /**
     * HITL 通用模板（message 事件照发；兼容现状调用）。
     *
     * @return 方案文本（即本轮最后一条 message 内容，供调用方落库）
     */
    public String runHITLStage(OrchestrationRequest req, String workflowTitle, StagePlan plan) {
        return runHITLStage(req, workflowTitle, plan, false);
    }

    /**
     * HITL 通用模板：workflow → 方案消息 → checkpoint 落库 → human_input/video_plan 事件
     * （发完即结束本轮，等表单提交 resume）。
     *
     * @param skipMessage 追问场景传 true：方案文本已由流式 message 增量发过（streamPlanWithMessage），
     *                    再发一次会重复出现在气泡里；human_input 事件的 formContent 不受影响
     * @return 方案文本（即本轮最后一条 message 内容，供调用方落库）
     */
    public String runHITLStage(OrchestrationRequest req, String workflowTitle, StagePlan plan, boolean skipMessage) {
        if (workflowTitle != null) {
            sendEvent(req, "workflow", Map.of("title", workflowTitle, "status", "node_started"));
        }
        if (!skipMessage) {
            sendEvent(req, "message", Map.of("content", plan.planText()));
        }
        String formToken = createCheckpoint(req.getConversation(), plan.action(), plan.planPayload(), STEP_EXECUTE,
                plan.planText(), plan.actions(), plan.models(), plan.recommended(), plan.reasons(),
                plan.imageModels(), plan.videoModels(), plan.assets());
        Map<String, Object> event = new java.util.LinkedHashMap<>(Map.of(
            "formToken", formToken, "taskId", "",
            "formContent", plan.planText(),
            "actions", plan.actions(),
            "expirationTime", OffsetDateTime.now().plus(CHECKPOINT_TTL).toString()));
        // 模型/参数选项与 LLM 推荐（非空才下发；旧前端/无配置时字段缺失，行为与现状一致）
        if (plan.models() != null && !plan.models().isEmpty()) event.put("models", plan.models());
        // aisplit 分镜确认卡片：图片/视频两组模型列表（前端渲染分区参数选择器）
        if (plan.imageModels() != null && !plan.imageModels().isEmpty()) event.put("imageModels", plan.imageModels());
        if (plan.videoModels() != null && !plan.videoModels().isEmpty()) event.put("videoModels", plan.videoModels());
        if (plan.recommended() != null && !plan.recommended().isEmpty()) event.put("recommended", plan.recommended());
        if (plan.reasons() != null && !plan.reasons().isEmpty()) event.put("reasons", plan.reasons());
        // 资产选择卡片：勾选清单下发（前端渲染多选；无资产/非选择卡片时字段缺失）
        if (plan.assets() != null && !plan.assets().isEmpty()) event.put("assets", plan.assets());
        sendEvent(req, plan.eventName(), event);
        return plan.planText();
    }

    /** 资产门禁 resume 结果：proceed=true 继续（prompt=本轮需求文本，assetIds=实际生效资产）；false=本轮已结束（澄清卡片已发） */
    public record AssetGateResume(boolean proceed, String prompt, List<String> assetIds) {}

    // ===== 资产联动（选择卡片载荷 + 关联性门禁，aisplit/video 两链共用） =====

    /** 资产 → 前端勾选卡片载荷（id/name/type/主图，供 human_input 事件 assets 字段） */
    public List<Map<String, Object>> buildAssetOptions(List<AssetVO> assets) {
        if (assets == null || assets.isEmpty()) return List.of();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (AssetVO a : assets) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", a.id());
            m.put("name", a.name());
            m.put("type", a.type());
            if (a.images() != null && !a.images().isEmpty()) m.put("image", a.images().getFirst().url());
            out.add(m);
        }
        return out;
    }

    /** 按资产 ID 子集过滤（null/空 → 空列表） */
    public List<AssetVO> pickAssets(List<AssetVO> assets, List<String> assetIds) {
        if (assets == null || assets.isEmpty() || assetIds == null || assetIds.isEmpty()) return List.of();
        return assets.stream().filter(a -> assetIds.contains(a.id())).toList();
    }

    /** 勾选资产 → 设定集文字（委托 StoryboardClient.buildSheetText；空列表返回空串） */
    public String assetSheetText(List<AssetVO> assets) {
        return StoryboardClient.buildSheetText(assets);
    }

    /** 勾选资产（逗号分隔 ID）的第一张图片 URL（作为视频首帧参照——人物形象直接由资产照片决定；
     *  纯文字描述生成的视频人物与资产完全不像，2026-08-17 用户反馈）；无资产/无图返回空串 */
    public String firstAssetImageUrl(String projectId, String assetIdsCsv) {
        if (projectId == null || assetIdsCsv == null || assetIdsCsv.isBlank()) return "";
        try {
            List<String> ids = java.util.Arrays.stream(assetIdsCsv.split(","))
                    .map(String::trim).filter(x -> !x.isBlank()).toList();
            if (ids.isEmpty()) return "";
            for (com.moon.moonagent.dto.response.AssetVO vo : storyboardClient.getProjectAssets(projectId)) {
                if (!ids.contains(vo.id())) continue;
                if (vo.images() != null && !vo.images().isEmpty() && vo.images().getFirst().url() != null) {
                    return vo.images().getFirst().url();
                }
            }
            return "";
        } catch (Exception e) {
            log.warn("获取资产首图失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 会话历史上下文文本（最近 max 条消息，按时间正序），供各编排 LLM 调用拼接——
     * 用户本条消息可能只有「重新生成/继续」等简短指令，真实需求在历史消息里，
     * 不读上下文会误判（如门禁判定资产不相关、方案生成跑偏）。读取失败/无消息返回空串。
     */
    public String historyContext(String conversationId, int max) {
        if (conversationId == null || conversationId.isBlank() || max <= 0) return "";
        try {
            List<com.moon.moonagent.entity.AgentMessage> msgs = messageMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.moon.moonagent.entity.AgentMessage>()
                            .eq(com.moon.moonagent.entity.AgentMessage::getConversationId, conversationId)
                            .orderByDesc(com.moon.moonagent.entity.AgentMessage::getCreatedAt)
                            .last("LIMIT " + max));
            if (msgs == null || msgs.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n\n【最近对话上下文（用于理解完整需求，按时间顺序）】\n");
            for (int i = msgs.size() - 1; i >= 0; i--) {
                com.moon.moonagent.entity.AgentMessage m = msgs.get(i);
                String c = m.getContent() == null ? "" : m.getContent().trim();
                if (c.isBlank()) continue;
                sb.append("user".equals(m.getRole()) ? "用户" : "助手").append("：").append(c).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("会话历史上下文读取失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 关联性门禁：提示词 × 勾选资产 判定。
     *
     * @param source 来源链标识（aisplit / video，存入 checkpoint plan 供 resume 分派）
     * @param picUrl video 链参考图（存 plan 供澄清后继续设计方案用；aisplit 传空串）
     * @return null=放行（相关/判定失败/无资产）；非 null=本轮已结束（弱关联澄清卡片已发），调用方直接 return
     */
    public String runAssetGate(OrchestrationRequest req, String prompt, List<AssetVO> chosenAssets, String source, String picUrl) {
        if (chosenAssets == null || chosenAssets.isEmpty()) return null;
        // 判定输入拼最近会话上下文：用户可能只说「重新生成」，真实需求在历史消息里（不读上下文会把资产误判为不相关）
        String promptWithCtx = prompt + historyContext(req.getConversation().getId(), 15);
        AssetRelevanceResult r = assetMatchingService.judgeRelevance(promptWithCtx, chosenAssets);
        if (r.relevant()) return null;
        // 弱关联：澄清上限未达 → 弹卡片；已达 → 提示后放行（复用 clarifyLimitReached 的提示消息）
        if (clarifyLimitReached(req.getConversation().getId(), req)) return null;
        List<String> ids = chosenAssets.stream().map(AssetVO::id).toList();
        String planText = "⚠ 检测到你勾选的资产与当前需求关联性不强：\n"
                + StoryboardClient.buildSheetText(chosenAssets)
                + "\n" + (r.reason() == null || r.reason().isBlank() ? "请讲清楚这些资产在本次创作中的用途。" : r.reason())
                + "\n\n请重新描述需求（讲清楚资产如何融入），或选择不使用资产继续。";
        return runHITLStage(req, null, new StagePlan(
                planText, "asset-gate",
                List.of(Map.of("source", source, "content", prompt, "assetIds", String.join(",", ids),
                        "picUrl", picUrl == null ? "" : picUrl)),
                "human_input",
                List.of(Map.of("id", "custom", "title", "✍ 重新描述需求（讲清楚资产用途）"),
                        Map.of("id", "asset-skip", "title", "不使用资产，直接生成"),
                        Map.of("id", "asset-force", "title", "仍然使用这些资产"))));
    }

    /**
     * 资产门禁澄清卡片 resume：按提交动作返回继续所需状态（{@link AssetGateResume}）。
     * asset-skip → 清空资产继续；asset-force → 保留资产继续；custom → 新文本重判（仍弱关联再弹卡，达上限放行）。
     *
     * @param chosenAssets 当前勾选的资产（含图，judgeRelevance 用）
     */
    public AssetGateResume resumeAssetGate(OrchestrationRequest req, AgentCheckpoint cp, List<AssetVO> chosenAssets) {
        String action = req.getAction();
        String originPrompt = planField(cp.getPlan(), "content");
        List<String> ids = parseAssetIds(planField(cp.getPlan(), "assetIds"));
        if ("asset-skip".equals(action)) return new AssetGateResume(true, originPrompt, List.of());
        if ("asset-force".equals(action)) return new AssetGateResume(true, originPrompt, ids);
        // custom：重新描述需求 → 重判
        String newPrompt = req.getCustomText();
        if (newPrompt == null || newPrompt.isBlank()) newPrompt = originPrompt;
        if (!ids.isEmpty() && chosenAssets != null && !chosenAssets.isEmpty()) {
            AssetRelevanceResult r = assetMatchingService.judgeRelevance(newPrompt, chosenAssets);
            if (!r.relevant()) {
                if (clarifyLimitReached(req.getConversation().getId(), req)) {
                    return new AssetGateResume(true, newPrompt, ids);
                }
                String planText = "⚠ 调整后的需求与资产关联性仍不强："
                        + (r.reason() == null || r.reason().isBlank() ? "" : r.reason())
                        + "\n\n请进一步讲清楚资产在本次创作中的用途，或选择不使用资产。";
                runHITLStage(req, null, new StagePlan(
                        planText, "asset-gate",
                        List.of(Map.of("source", planField(cp.getPlan(), "source"),
                                "content", newPrompt, "assetIds", String.join(",", ids),
                                "picUrl", planField(cp.getPlan(), "picUrl"))),
                        "human_input",
                        List.of(Map.of("id", "custom", "title", "✍ 重新描述需求（讲清楚资产用途）"),
                                Map.of("id", "asset-skip", "title", "不使用资产，直接生成"),
                                Map.of("id", "asset-force", "title", "仍然使用这些资产"))));
                return new AssetGateResume(false, newPrompt, ids);
            }
        }
        return new AssetGateResume(true, newPrompt, ids);
    }

    /** checkpoint plan 里的资产 ID 列表（逗号分隔字符串 → List；宽松解析） */
    private List<String> parseAssetIds(String s) {
        if (s == null || s.isBlank()) return List.of();
        return java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    /**
     * resume 通用收尾模板：workflow → message（结果内容）→ confirm_result → message_end。
     * 各链只填充执行工具结果（executeTool 钩子），收尾事件序列全部复用。
     *
     * @param result 含 {content, confirm(Map), sceneCount(long)} 三键
     */
    public void resumeStage(OrchestrationRequest req, String workflowTitle, Map<String, Object> result) {
        if (workflowTitle != null) {
            sendEvent(req, "workflow", Map.of("title", workflowTitle, "status", "node_started"));
        }
        String content = String.valueOf(result.getOrDefault("content", ""));
        sendMessage(req, content);
        @SuppressWarnings("unchecked")
        Map<String, Object> confirm = (Map<String, Object>) result.getOrDefault("confirm", Map.of());
        sendEvent(req, "confirm_result", confirm);
        long sceneCount = result.get("sceneCount") instanceof Number n ? n.longValue() : -1L;
        sendEvent(req, "message_end", Map.of("messageId", "", "sceneCount", sceneCount, "content", content));
    }

    /**
     * 异步视频生成（resume/generate_video 与图生视频方案确认共用）：
     * 创建任务（内部落 agent_assets queued 行）→ 立即发 task_accepted 事件 → 结束 SSE，
     * 后台虚拟线程轮询直至终态并更新资产行（前端轮询 GET /api/agent/tasks/{taskId} 取结果）。
     *
     * @param params 用户选择的生成参数（model/resolution/duration/aspectRatio；空=未选择，走默认）
     * @return taskId（创建失败返回 null，已发 error 事件）
     */
    public String startVideoGenerationAsync(OrchestrationRequest req, String prompt, String duration,
                                            String aspectRatio, String source) {
        return startVideoGenerationAsync(req, prompt, duration, aspectRatio, source, Map.of());
    }

    /** 带用户参数选择的异步视频生成（params 显式优先，未提供键回退原值/默认） */
    public String startVideoGenerationAsync(OrchestrationRequest req, String prompt, String duration,
                                            String aspectRatio, String source, Map<String, String> params) {
        String model = params.getOrDefault("model", null);
        String resolution = params.getOrDefault("resolution", null);
        String effDuration = params.getOrDefault("duration", duration);
        String effAspectRatio = params.getOrDefault("aspectRatio", aspectRatio);
        String taskId = generationService.createVideoTask(
                req.getConversation(), null, prompt, model, resolution, null,
                effAspectRatio, effDuration, null, null, source);
        if (taskId == null || taskId.isBlank()) {
            sendFriendlyError(req, null, "视频任务暂时创建不了，请稍后重试。");
            return null;
        }
        // 立即受理：task_accepted → 本轮 SSE 结束（前端转轮询，不再同步阻塞 7.5min）
        sendEvent(req, "task_accepted", Map.of("taskId", taskId, "message", "视频任务已受理，正在排队生成"));
        agentExecutor.submit(() -> pollVideoTaskAndUpdate(taskId));
        return taskId;
    }

    /** 后台轮询视频任务直至终态（90×5s ≈ 7.5min 上限），终态更新 agent_assets 行 */
    private void pollVideoTaskAndUpdate(String taskId) {
        try {
            for (int i = 0; i < 90; i++) {
                if (Thread.currentThread().isInterrupted()) return;
                Map<String, String> result = generationService.pollVideoTask(taskId);
                String status = result.get("status");
                String progress = result.get("progress");
                log.info("视频生成后台轮询: taskId={}, 第 {}/90 次, status={}{}", taskId, i + 1, status,
                        progress != null && !progress.isBlank() ? ", progress=" + progress : "");
                if ("completed".equals(status) || "succeeded".equals(status)) {
                    // pollVideoTask 内部已处理下载：succeeded→completed+videoUrl
                    updateVideoAsset(taskId, "completed", result.get("videoUrl"), null);
                    return;
                }
                if ("failed".equals(status)) {
                    // 上游失败原因 LLM 友好化后落库（前端轮询展示友好文案，不露英文报错）
                    String friendly = friendlyErrorText(result.getOrDefault("error", ""), "视频生成失败了，请稍后重试。");
                    updateVideoAsset(taskId, "failed", null, friendly);
                    return;
                }
                // 运行中：status 置 running（progress 无存储列，仅日志可见；前端轮询态即可）
                updateVideoAsset(taskId, "running", null, null);
                Thread.sleep(5000);
            }
            updateVideoAsset(taskId, "failed", null, "视频生成超时，请重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("视频后台轮询失败: taskId={}, error={}", taskId, e.getMessage(), e);
            updateVideoAsset(taskId, "failed", null, "视频生成失败，请稍后重试");
        }
    }

    /** 按 taskId 更新 agent_assets 行（幂等；行不存在忽略——scene 视频无资产行） */
    private void updateVideoAsset(String taskId, String status, String url, String error) {
        try {
            AgentAsset asset = assetMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentAsset>()
                            .eq(AgentAsset::getTaskId, taskId).last("LIMIT 1"));
            if (asset == null) return;
            asset.setStatus(status);
            if (url != null && !url.isBlank()) asset.setUrl(url);
            if (error != null && !error.isBlank()) asset.setError(error);
            assetMapper.updateById(asset);
        } catch (Exception e) {
            log.warn("视频资产状态更新失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    /** checkpoint plan JSON 取字段（宽松解析，缺失返回空串） */
    public String planField(String planJson, String field) {
        if (planJson == null || planJson.isBlank()) return "";
        try {
            var root = objectMapper.readTree(planJson);
            var items = root.path("items");
            if (items.isArray() && !items.isEmpty()) {
                return items.get(0).path(field).asText("");
            }
            return root.path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** checkpoint plan JSON 取 items[0] 的 List 字段（宽松解析，缺失/失败返回空 list） */
    public List<Map<String, Object>> planListField(String planJson, String field) {
        if (planJson == null || planJson.isBlank()) return List.of();
        try {
            var items = objectMapper.readTree(planJson).path("items");
            if (items.isArray() && !items.isEmpty() && items.get(0).has(field)) {
                List<Map<String, Object>> out = new java.util.ArrayList<>();
                for (var e : items.get(0).path(field)) out.add(objectMapper.convertValue(e, Map.class));
                return out;
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    /** checkpoint plan JSON → AgentSceneItem 列表（宽松解析） */
    public List<AgentSceneItem> parsePlanScenes(String planJson) {
        if (planJson == null || planJson.isBlank()) return List.of();
        try {
            var root = objectMapper.readTree(planJson);
            var items = root.path("items");
            if (!items.isArray()) return List.of();
            List<AgentSceneItem> result = new java.util.ArrayList<>();
            for (var it : items) {
                result.add(new AgentSceneItem(
                    it.path("sceneNumber").asInt(0),
                    it.path("scriptContent").asText(""),
                    it.path("imagePrompt").asText(""),
                    it.path("videoPrompt").asText(""),
                    it.path("negativePrompt").asText(""),
                    it.path("cameraMovement").asText(""),
                    it.path("shotType").asText(""),
                    it.path("soundDesign").asText("")));
            }
            return result;
        } catch (Exception e) {
            log.warn("checkpoint plan 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** checkpoint plan JSON → 分镜资产关联列表（宽松解析；plan 的 items 每项含 sceneNumber/assetIds 时返回） */
    public List<com.moon.moonagent.ai.agent.SceneAssetMatch> parsePlanAssetMatches(String planJson) {
        if (planJson == null || planJson.isBlank()) return List.of();
        try {
            var root = objectMapper.readTree(planJson);
            var items = root.path("items");
            if (!items.isArray()) return List.of();
            List<com.moon.moonagent.ai.agent.SceneAssetMatch> result = new java.util.ArrayList<>();
            for (var it : items) {
                if (!it.has("assetIds") || !it.path("assetIds").isArray()) continue;
                List<String> ids = new java.util.ArrayList<>();
                for (var id : it.path("assetIds")) ids.add(id.asText(""));
                result.add(new com.moon.moonagent.ai.agent.SceneAssetMatch(
                        it.path("sceneNumber").asInt(0),
                        ids.stream().filter(x -> !x.isBlank()).toList()));
            }
            return result;
        } catch (Exception e) {
            log.warn("checkpoint plan 资产关联解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 分镜概要（HITL 确认卡片文本：全量展示所有镜头，单条不截断） */
    public String summarizeScenes(List<Map<String, Object>> scenes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scenes.size(); i++) {
            Map<String, Object> s = scenes.get(i);
            String content = s.get("scriptContent") instanceof String c ? c : "";
            sb.append(i + 1).append(". ").append(content).append("\n");
        }
        return sb.toString();
    }

    // ===== SSE 事件 =====

    /**
     * 错误友好化：调 LLM 把上游原始错误（safety 审核/网络/超时等）翻译成给用户看的自然中文回复。
     * 用户不该看到英文报错原文——踩线提示改措辞、网络提示稍后重试、其余通用文案。
     * LLM 调用失败回退 fallback（保证永不空手）。后台线程（视频轮询失败）也可用。
     */
    public String friendlyErrorText(String rawError, String fallback) {
        try {
            String raw = rawError == null || rawError.isBlank() ? "(无错误详情)" : rawError;
            String content = planClient().prompt()
                    .system(promptConfig.get("orchestrator/friendly-error"))
                    .user(raw)
                    .call()
                    .content();
            if (content != null && !content.isBlank()) {
                return content.trim();
            }
        } catch (Exception e) {
            log.warn("错误友好化 LLM 调用失败，回退固定文案: {}", e.getMessage());
        }
        return fallback;
    }

    /** 友好错误收尾：发 message（完整文案）+ message_end（正常结束，非 error 事件），返回文案供落库 */
    public String sendFriendlyError(OrchestrationRequest req, String rawError, String fallback) {
        String friendly = friendlyErrorText(rawError, fallback);
        sendEvent(req, "message", Map.of("content", friendly));
        sendEvent(req, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", friendly));
        return friendly;
    }

    /**
     * 图片生成失败自救：LLM 判断错误是否审核拒绝（safety/rejected/violations）。
     * 是 → 重写提示词（保留原创作意图、剔除触发词）自动重试，message 说明给用户；
     * 否 → recoverable=false，message 为友好告知。LLM 失败兜底不可恢复（不阻塞主流程）。
     */
    public ImageRecoveryResult attemptImageRecovery(String originalPrompt, String rawError) {
        try {
            String raw = rawError == null || rawError.isBlank() ? "(无错误详情)" : rawError;
            String content = planClient().prompt()
                    .system(promptConfig.get("orchestrator/image-recovery"))
                    .user("原提示词：\n" + originalPrompt + "\n\n上游错误：\n" + raw)
                    .call()
                    .content();
            if (content != null && !content.isBlank()) {
                return new BeanOutputConverter<>(ImageRecoveryResult.class).convert(content);
            }
        } catch (Exception e) {
            log.warn("图片生成自救 LLM 调用失败，按不可恢复处理: {}", e.getMessage());
        }
        return new ImageRecoveryResult(false, "", "图片没生成出来，请稍后重试或调整一下描述。");
    }

    /** SseEmitter 事件发送（前端断开忽略）；message 事件同步更新 req.lastMessage（per-request，并发安全） */
    public void sendEvent(OrchestrationRequest req, String eventName, Map<String, Object> data) {
        if ("message".equals(eventName) && data.get("content") != null) {
            req.setLastMessage(String.valueOf(data.get("content")));
        }
        try {
            req.getEmitter().send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            log.debug("SseEmitter 发送失败（前端可能已断开）: event={}", eventName);
        }
    }

    /** 便捷转发：方案文本 → message 事件（流式增量时逐段更新 lastMessage） */
    public void sendMessage(OrchestrationRequest req, String content) {
        sendEvent(req, "message", Map.of("content", content));
    }
}
