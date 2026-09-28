package com.moon.moonagent.ai.agent.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.orchestration.OrchestrationMode;
import com.moon.moonagent.ai.agent.AgentInterruptException;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.PlanField;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.ai.agent.AssetRelevanceResult;
import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.storyboard.VideoGenerationPolicy;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.config.PromptResolver;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentScope turn 共享支持组件（组合优于继承：避免手写构造器，per-request 状态由
 * {@link OrchestrationRequest#lastMessage} 承担，组件本身无状态、并发安全）。
 *
 * <p>职责：SSE 事件发送、HITL checkpoint 落库/解析、剧本优化/分镜方案/图片提示词/视频方案
 * 等 LLM 调用（复用网关默认文本模型（动态获取），超时 120s）、HITL 通用模板
 * {@link #runHITLStage}。
 */
@Component
@RequiredArgsConstructor
public class AgentRuntimeSupport {

    @Autowired(required = false)
    private transient ApplicationEventPublisher taskEventPublisher;

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeSupport.class);

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
    private final AgentSessionState sessionState;
    private final ModelOptionsAssembler modelOptionsAssembler;

    /** 视频异步后台轮询专用 executor（虚拟线程，不占池） */
    private final ExecutorService agentExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> activeVideoPolls = ConcurrentHashMap.newKeySet();

    /** 主计划 LLM（懒加载，复用网关默认对话模型，超时由 ai.agent.plan-timeout-seconds 控制） */
    private volatile ChatClient planClient;
    /** 参数推荐 LLM（可降级步骤，超时由 ai.agent.recommend-timeout-seconds 控制） */
    private volatile ChatClient recommendClient;
    /** 轻量意图判定 LLM（超时由 ai.agent.intent-timeout-seconds 控制） */
    private volatile ChatClient intentClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 澄清上限判定：本次 type=0 记 +1；未达上限返回 false（调用方正常追问并结束本轮）；
     * 已达上限返回 true（调用方跳过追问，以用户原始需求为默认方案继续）。
     */
    public boolean clarifyLimitReached(String conversationId, OrchestrationRequest req) {
        int n = sessionState.incrClarifyCount(conversationId);
        if (n > agentConfig.getMaxClarifyRounds()) {
            sessionState.resetClarify(conversationId);
            sendMessage(req, "已按你的原始需求直接生成方案，可在确认卡片上调整。");
            return true;
        }
        return false;
    }

    /** 澄清计数清零（type=1 有进展 / 非 aisplit 轮调用）——委托 AgentSessionState */
    public void resetClarify(String conversationId) {
        sessionState.resetClarify(conversationId);
    }

    /** 会话删除时清理全部内存态——委托 AgentSessionState */
    public void cleanupOnDelete(String conversationId) {
        sessionState.cleanupOnDelete(conversationId);
    }

    /** 置位会话打断标志（「停止生成」/ SSE 断开触发）——委托 AgentSessionState */
    public void interrupt(String conversationId) {
        sessionState.interrupt(conversationId);
    }

    /** 清除会话打断标志（新一轮编排启动/收尾调用，恢复正常流）——委托 AgentSessionState */
    public void clearInterrupt(String conversationId) {
        sessionState.clearInterrupt(conversationId);
    }

    /** 是否收到打断信号——委托 AgentSessionState */
    public boolean isInterrupted(String conversationId) {
        return sessionState.isInterrupted(conversationId);
    }

    /**
     * 打断检查（流式 LLM doOnNext / 阶段边界调用）：置位后抛 {@link AgentInterruptException}，
     * 触发编排协作式取消——中断打字机、跳过被截断回答的落库、提前释放会话锁。
     */
    public void checkInterrupted(OrchestrationRequest req) {
        if (req != null && sessionState.isInterrupted(req.getConversation().getId())) {
            throw new AgentInterruptException();
        }
    }

    /** 不满意调整 +1，返回新值——委托 AgentSessionState */
    public int incrRegenCount(String conversationId) {
        return sessionState.incrRegenCount(conversationId);
    }

    /** 写库成功/达上限清零——委托 AgentSessionState */
    public void clearRegenCount(String conversationId) {
        sessionState.clearRegenCount(conversationId);
    }

    /** 记录本轮勾选资产——委托 AgentSessionState */
    public void setSessionAssets(String conversationId, List<String> ids) {
        sessionState.setSessionAssets(conversationId, ids);
    }

    /** 本轮已确认资产 ID（无记录返回空列表）——委托 AgentSessionState */
    public List<String> getSessionAssets(String conversationId) {
        return sessionState.getSessionAssets(conversationId);
    }

    /** 本轮是否已选过资产（防 handle 重入重复弹资产卡片）——委托 AgentSessionState */
    public boolean hasSessionAssets(String conversationId) {
        return sessionState.hasSessionAssets(conversationId);
    }

    /** 本轮结束清理——委托 AgentSessionState */
    public void clearSessionAssets(String conversationId) {
        sessionState.clearSessionAssets(conversationId);
    }

    /** 计数写入 checkpoint.plan（createCheckpoint 内调用）——重启/刷新后 resume 可恢复 */
    void persistCountersToPlan(Map<String, Object> plan, String conversationId) {
        sessionState.persistCountersToPlan(plan, conversationId);
    }

    /** 从 checkpoint.plan 恢复计数（resume 消费后调用）——重启后内存计数不丢 */
    public void restoreCountersFromPlan(String conversationId, String planJson) {
        sessionState.restoreCountersFromPlan(conversationId, planJson);
    }

    // ===== 结构化输出 record =====

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
        return callStructuredGate("script-optimize", "orchestrator/script-optimize", content, req,
                ScriptOptimizeResult.class, new ScriptOptimizeResult(0, "已理解你的需求，请继续补充。", null, List.of()));
    }

    /**
     * 需求澄清 gate：分析用户分镜需求的模糊程度，输出多维度澄清问题。
     * 复用 ScriptOptimizeResult 结构（type=0 需澄清 / type=1 已明确直接跳过）。
     */
    public ScriptOptimizeResult callRequirementClarify(String content, OrchestrationRequest req) {
        // 失败直接跳过澄清，不阻塞主流程
        return callStructuredGate("requirement-clarify", "orchestrator/requirement-clarify", content, req,
                ScriptOptimizeResult.class, new ScriptOptimizeResult(1, "", null, List.of()));
    }

    /** 分镜方案设计（message 字段流式增量转发） */
    public StoryboardPlanResult callStoryboardPlan(String script, OrchestrationRequest req) {
        return callStructuredGate("storyboard-plan", "orchestrator/storyboard-plan", script, req,
                StoryboardPlanResult.class, new StoryboardPlanResult(1, "已根据剧本生成方案。", List.of()));
    }

    // ===== 结构化输出容错（gate/方案 LLM 偶发吐人话而非 JSON，e2e 实测「好的」撞车）=====

    /**
     * 结构化 gate 统一调用：流式调 LLM → 容错解析；若 LLM 输出纯人话（非 JSON）导致解析失败，
     * 安静地带「只输出 JSON」重试一次（不转发 message 字段、不重复流式），仍失败回退 fallback。
     * 系统提示词按需加载：skill body {@code ### stepId} 优先，未命中回退 promptConfig {@code fallbackKey}。
     */
    private <T> T callStructuredGate(String stepId, String fallbackKey, String content, OrchestrationRequest req,
                                     Class<T> type, T fallback) {
        try {
            String raw = streamPlanWithMessage(prompt(req, stepId, fallbackKey, null), content, req);
            return convertStructured(raw, type);
        } catch (AgentInterruptException e) {
            throw e; // 用户打断：终止修复重试，交给编排壳层收尾
        } catch (Exception e) {
            log.warn("{} LLM 输出非 JSON，修复重试一次: {}", fallbackKey, e.getMessage());
            try {
                // 只重试一次：追加「只输出 JSON」约束的安静调用（非流式，不再向用户转发 message）
                String raw2 = callQuiet(prompt(req, stepId, fallbackKey, null)
                        + "\n\n注意：你上一次输出不是 JSON。现在请只输出一个 JSON 对象，不要任何说明、思考或前言。",
                        content, req);
                if (raw2 != null && !raw2.isBlank()) return convertStructured(raw2, type);
            } catch (Exception e2) {
                log.warn("{} LLM 修复重试仍失败: {}", fallbackKey, e2.getMessage());
            }
            return fallback;
        }
    }

    /** 容错解析：直接转换；失败则剥掉 JSON 对象前后的人话再试（如「好的，方案如下：{...}」） */
    private <T> T convertStructured(String raw, Class<T> type) {
        try {
            return new BeanOutputConverter<>(type).convert(raw);
        } catch (Exception e) {
            String json = stripJsonObject(raw);
            if (json != null && !json.equals(raw)) {
                try { return new BeanOutputConverter<>(type).convert(json); }
                catch (Exception e2) { /* 落到最外层抛给调用方做修复重试 */ }
            }
            throw new RuntimeException("LLM 未输出合法 JSON: " + e.getMessage(), e);
        }
    }

    /** 摘取 raw 中第一个 {@code {} 到最后一个 {@code } 之间的 JSON 对象；无对象（纯人话）返回 null */
    static String stripJsonObject(String raw) {
        if (raw == null) return null;
        int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
        return (a >= 0 && b > a) ? raw.substring(a, b + 1) : null;
    }

    /** 安静调用（非流式、不转发 message 字段）：供修复重试用 */
    private String callQuiet(String systemPrompt, String userContent, OrchestrationRequest req) {
        try {
            beginModelCall(req);
            ChatResponse response = planClient().prompt().options(planOptions())
                    .system(systemPrompt)
                    .user(userContent)
                    .call()
                    .chatResponse();
            recordUsage(req, response);
            return response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : "";
        } catch (Exception e) {
            log.warn("修复重试 LLM 调用失败: {}", e.getMessage());
            return "";
        }
    }

    /** Legacy helper retained only for internal services; AgentScope owns user-facing skill prompts. */
    public String prompt(OrchestrationRequest req, String stepId, String fallbackKey, Map<String, String> vars) {
        return promptConfig.get(fallbackKey, vars == null ? Map.of() : vars);
    }

    /**
     * 调用 planClient 并保留真实推理摘要。
     *
     * <p>Spring AI 2.0 的 {@code stream().chatResponse()} 在 OpenAI SDK 转换增量 chunk 时会
     * 丢弃非标准 {@code reasoning_content}（chunk 的 reasoningContent 恒为空），因此这里使用
     * 非流式 {@code call().chatResponse()} 读取元数据。正文仍从结构化 JSON 的 message 字段
     * 转发给前端，不暴露原始 JSON。
     *
     * @return 完整响应文本（调用方 BeanOutputConverter 解析）
     */
    public String streamPlanWithMessage(String systemPrompt, String userContent, OrchestrationRequest req) {
        OffsetDateTime started = OffsetDateTime.now();
        String raw = "";
        try {
            checkInterrupted(req);
            beginModelCall(req);
            ChatResponse response = planClient().prompt()
                    .options(planOptions())
                    .system(systemPrompt)
                    .user(userContent)
                    .call()
                    .chatResponse();
            recordUsage(req, response);
            checkInterrupted(req);
            AssistantMessage output = response != null && response.getResult() != null
                    ? response.getResult().getOutput() : null;
            if (output != null && output.getText() != null) raw = output.getText();

            publishReasoning(req, output, started, "模型未返回可见推理过程，已完成方案分析。");

            String msg = extractMessageField(raw);
            if (msg != null && !msg.isBlank()) {
                sendEvent(req, "message", Map.of("content", msg));
            }
        } catch (AgentInterruptException e) {
            throw e;
        } catch (Exception e) {
            log.warn("planClient 调用失败: {}", e.getMessage());
        }
        return raw;
    }

    /** 从 Spring AI AssistantMessage metadata 提取网关返回的推理内容。 */
    private String extractReasoning(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        for (String key : new String[]{"reasoning_content", "reasoningContent", "thoughts", "thinking"}) {
            Object value = metadata.get(key);
            if (value == null) continue;
            String text = value instanceof String s ? s : String.valueOf(value);
            if (text != null && !text.isBlank()) return text;
        }
        return null;
    }

    private String summarizeReasoning(String text) {
        if (text == null || text.isBlank()) return "";
        String trimmed = text.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
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
    public String callImagePrompt(String content, OrchestrationRequest req) {
        try {
            ChatResponse response = retryTransient(() -> {
                beginModelCall(req);
                return planClient().prompt().options(planOptions())
                        .system(prompt(req, "image-prompt", "orchestrator/image-prompt", null))
                        .user(content)
                        .call()
                        .chatResponse();
            });
            recordUsage(req, response);
            String raw = response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
            return raw != null && !raw.isBlank() ? raw.trim() : content;
        } catch (Exception e) {
            log.warn("图片提示词 LLM 调用失败: {}", e.getMessage());
            return content;
        }
    }

    /**
     * 非流式 LLM 文本生成（不转发 message 事件，无 SSE 副作用）。
     * 供扩写方案等需要「取整段文本、再单独展示」的场景使用；失败返回空串。
     */
    public String callPlainText(String systemPrompt, String userContent, OrchestrationRequest req) {
        return callPlainText(systemPrompt, userContent, req, "已完成当前文本生成，正在整理结果。");
    }

    /** 非流式文本生成；保留模型 reasoning_content，并立即通过 reasoning_delta 下发给前端。 */
    public String callPlainText(String systemPrompt, String userContent, OrchestrationRequest req,
                                String fallbackReason) {
        OffsetDateTime started = OffsetDateTime.now();
        try {
            ChatResponse response = retryTransient(() -> {
                beginModelCall(req);
                return planClient().prompt()
                        .options(planOptions())
                        .system(systemPrompt)
                        .user(userContent)
                        .call()
                        .chatResponse();
            });
            recordUsage(req, response);
            AssistantMessage output = response != null && response.getResult() != null
                    ? response.getResult().getOutput() : null;
            String raw = output == null ? "" : (output.getText() == null ? "" : output.getText());
            publishReasoning(req, output, started, fallbackReason);
            return raw == null ? "" : raw.trim();
        } catch (Exception e) {
            log.warn("LLM 文本生成失败: {}", e.getMessage());
            return "";
        }
    }

    /** 把推理快照写入当前 request/trace，并实时下发 reasoning_delta。 */
    public void publishReasoning(OrchestrationRequest req, AgentReasoningSnapshot snapshot) {
        if (req == null || snapshot == null) return;
        req.setReasoning(snapshot);
        if (req.getTrace() != null) req.getTrace().reasoning(snapshot);
        sendEvent(req, "reasoning_delta", Map.of(
                "details", snapshot.details() == null ? "" : snapshot.details(),
                "summary", snapshot.summary() == null ? "" : snapshot.summary(),
                "durationMs", snapshot.durationMs(),
                "source", snapshot.source() == null ? "" : snapshot.source()));
    }

    /** 从 AssistantMessage 提取 reasoning_content；模型未返回时使用调用方提供的阶段摘要。 */
    public void publishReasoning(OrchestrationRequest req, AssistantMessage output,
                                 OffsetDateTime started, String fallbackReason) {
        String reason = extractReasoning(output == null ? null : output.getMetadata());
        String reasonText = reason == null || reason.isBlank() ? fallbackReason : reason;
        long durationMs = started == null ? 0L : Duration.between(started, OffsetDateTime.now()).toMillis();
        publishReasoning(req, new AgentReasoningSnapshot(
                summarizeReasoning(reasonText),
                reasonText,
                durationMs,
                reason == null || reason.isBlank() ? "trace-summary" : gatewayModelService.getDefaultTextModel()));
    }

    /**
     * 带参数推荐的图片方案生成：LLM 同时输出提示词 + 推荐生成参数（模型/尺寸/质量）。
     * 复用 callVideoPlan 的结构化输出模式，返回 ImagePlanResult。
     */
    public ImagePlanResult callImagePromptWithParams(String content, String modelOptionsText, OrchestrationRequest req) {
        boolean withParams = modelOptionsText != null && !modelOptionsText.isBlank();
        String systemPrompt = prompt(req, "image-prompt-with-params", "orchestrator/image-prompt-with-params",
                Map.of("modelOptionsText", withParams ? modelOptionsText : ""));
        try {
            ChatResponse response = retryTransient(() -> {
                beginModelCall(req);
                return planClient().prompt().options(planOptions())
                        .system(systemPrompt)
                        .user(content)
                        .call()
                        .chatResponse();
            });
            recordUsage(req, response);
            String raw = response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
            // 解析 JSON：prompt 必须有，params/reasons 可选（LLM 未按格式输出时兜底空 Map）
            ImagePlanResult result = new BeanOutputConverter<>(ImagePlanResult.class).convert(raw);
            if (result == null || result.prompt() == null || result.prompt().isBlank()) {
                // 解析失败：退化为纯文本提示词（与 callImagePrompt 行为一致）
                String fallback = callImagePrompt(content, req);
                return new ImagePlanResult(fallback);
            }
            return new ImagePlanResult(result.prompt(),
                    result.params() == null ? Map.of() : result.params(),
                    result.reasons() == null ? Map.of() : result.reasons());
        } catch (Exception e) {
            log.warn("图片方案 LLM 调用失败，退化为纯提示词: {}", e.getMessage());
            String fallback = callImagePrompt(content, req);
            return new ImagePlanResult(fallback);
        }
    }

    /**
     * 图片需求澄清（无图链 gate）：LLM 判断用户描述是否足以生成明确图片。
     * type=1 需求明确（可继续出方案）；type=0 关键信息缺失需追问（message 只问一个最关键的问题，options 2~4 个）。
     * 用户回复只要提供了任何有效信息就直接生成方案；仅回复为空或与图片生成完全无关才 type=0。
     * LLM 调用失败 → type=1 放行（不阻塞出方案）。
     */
    public ImageClarifyResult callImageClarify(String content, OrchestrationRequest req) {
        try {
            ChatResponse response = retryTransient(() -> {
                beginModelCall(req);
                return planClient().prompt().options(planOptions())
                        .system(prompt(req, "image-clarify", "orchestrator/image-clarify", null))
                        .user(content)
                        .call()
                        .chatResponse();
            });
            recordUsage(req, response);
            String raw = response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
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
                    prompt(req, "video-clarify", "orchestrator/video-clarify", null),
                    content, req);
            return new BeanOutputConverter<>(ImageClarifyResult.class).convert(raw);
        } catch (Exception e) {
            log.warn("视频需求澄清 LLM 调用失败（降级跳过）: {}", e.getMessage());
            return new ImageClarifyResult(1, "", List.of());
        }
    }

    /** 无参考图视频：LLM 生成视频方案（无模型选项，兼容现状调用） */
    public VideoPlanResult callVideoPlan(String content, OrchestrationRequest req) {
        return callVideoPlan(content, null, req);
    }

    /**
     * 图片修改方向选项（refine「继续完善」时调用）：基于当前图片方案，
     * LLM 从不同维度（场景/服装/氛围/画风等，按方案内容动态）给出 2~4 个修改方向选项。
     * 解析失败/空输出 → 兜底通用 4 方向（保证卡片始终有选项可点）。
     */
    public PicRefineOptionsResult callPicRefineOptions(String basePrompt, boolean hasSource, OrchestrationRequest req) {
        try {
            String sourceNote = hasSource
                    ? prompt(req, "pic-refine-options-source-note", "orchestrator/pic-refine-options-source-note", null) : "";
            ChatResponse response = retryTransient(() -> {
                beginModelCall(req);
                return planClient().prompt().options(planOptions())
                        .system(prompt(req, "pic-refine-options", "orchestrator/pic-refine-options",
                                Map.of("hasSourceNote", sourceNote)))
                        .user("当前图片方案：" + basePrompt)
                        .call()
                        .chatResponse();
            });
            recordUsage(req, response);
            String raw = response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
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
    public VideoPlanResult callVideoPlan(String content, String modelOptionsText, OrchestrationRequest req) {
        try {
            boolean withParams = modelOptionsText != null && !modelOptionsText.isBlank();
            String system = prompt(req, "video-plan", "orchestrator/video-plan",
                    Map.of("modelOptionsText", withParams ? modelOptionsText : ""));
            ChatResponse response = retryTransient(() -> {
                beginModelCall(req);
                return planClient().prompt().options(planOptions())
                        .system(system)
                        .user(content)
                        .call()
                        .chatResponse();
            });
            recordUsage(req, response);
            String raw = response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
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

    /** 瞬态判定：HTTP 429/5xx（Spring 状态码异常）或连接失败；超时不重试。 */
    private static boolean isTransient(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof org.springframework.web.client.HttpStatusCodeException sce) {
                int s = sce.getStatusCode().value();
                if (s == 429 || s >= 500) return true;
            }
            if (c instanceof java.net.ConnectException) {
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
                    planClient = buildClient(agentConfig.getPlanTimeoutSeconds());
                }
            }
        }
        return planClient;
    }

    /** 懒加载推荐客户端：超时后直接使用模型默认参数，不重试。 */
    private ChatClient recommendClient() {
        if (recommendClient == null) {
            synchronized (this) {
                if (recommendClient == null) {
                    recommendClient = buildClient(agentConfig.getRecommendTimeoutSeconds());
                }
            }
        }
        return recommendClient;
    }

    /** 懒加载轻量意图客户端：超时按不取消处理。 */
    private ChatClient intentClient() {
        if (intentClient == null) {
            synchronized (this) {
                if (intentClient == null) {
                    intentClient = buildClient(agentConfig.getIntentTimeoutSeconds());
                }
            }
        }
        return intentClient;
    }

    private ChatClient buildClient(int timeoutSeconds) {
        return chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(gatewayModelService.getDefaultTextModel())
                        .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds))))
                .build();
    }

    private OpenAiChatOptions.Builder planOptions() {
        return requestOptions(agentConfig.getPlanTimeoutSeconds());
    }

    private OpenAiChatOptions.Builder recommendOptions() {
        return requestOptions(agentConfig.getRecommendTimeoutSeconds());
    }

    private OpenAiChatOptions.Builder intentOptions() {
        return requestOptions(agentConfig.getIntentTimeoutSeconds());
    }

    static OpenAiChatOptions.Builder requestOptions(String model, int timeoutSeconds) {
        return OpenAiChatOptions.builder()
                .model(model)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
    }

    private OpenAiChatOptions.Builder requestOptions(int timeoutSeconds) {
        return requestOptions(gatewayModelService.getDefaultTextModel(), timeoutSeconds);
    }

    // ===== 模型能力选项（网关 fetchModels 透传，卡片/LLM 选参共用） =====

    /** 网关模型列表（卡片下拉用）——委托 ModelOptionsAssembler */
    public List<Map<String, Object>> buildModels(String type) {
        return modelOptionsAssembler.buildModels(type);
    }

    /** LLM 选参用的人类可读选项文本——委托 ModelOptionsAssembler */
    public String buildModelOptionsText(String type) {
        return modelOptionsAssembler.buildModelOptionsText(type);
    }

    // ===== HITL 卡按钮 =====

    /** 直接使用状态感知的 manifest，避免为按钮文案额外调用一次 LLM。 */
    public List<Map<String, Object>> buildHITLCard(OrchestrationRequest req, String planText,
                                                   List<Map<String, Object>> manifestActions) {
        return manifestActions;
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
            ChatResponse response = retryTransient(() -> {
                beginModelCall(req);
                return recommendClient().prompt().options(recommendOptions())
                        .system(prompt(req, "scene-params-recommend", "orchestrator/scene-params-recommend", null))
                        .user("剧本：\n" + script
                                + "\n\n可选生图模型与参数：\n" + (imageOpts.isBlank() ? "（无）" : imageOpts)
                                + "\n可选视频模型与参数：\n" + (videoOpts.isBlank() ? "（无）" : videoOpts))
                        .call()
                        .chatResponse();
            });
            recordUsage(req, response);
            String raw = response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
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
        return createCheckpoint(conversation, action, planPayload, step, formContent, actions,
                models, recommended, reasons, imageModels, videoModels, assets, null);
    }

    public String createCheckpoint(AgentConversation conversation, String action,
                                   List<Map<String, Object>> planPayload, String step,
                                   String formContent, List<Map<String, Object>> actions,
                                   List<Map<String, Object>> models,
                                   Map<String, String> recommended,
                                   Map<String, String> reasons,
                                   List<Map<String, Object>> imageModels,
                                   List<Map<String, Object>> videoModels,
                                   List<Map<String, Object>> assets,
                                   Consumer<AgentCheckpoint> customizer) {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setConversationId(conversation.getId());
        OrchestrationMode mode = OrchestrationMode.from(conversation.getOrchestrationMode());
        cp.setOwnerEngine(mode.name());
        cp.setStateSchemaVersion(1);
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
        if (customizer != null) customizer.accept(cp);
        checkpointMapper.insert(cp);
        if (cp.getRuntimeSessionId() != null || cp.getRuntimeReplyId() != null
                || cp.getRuntimeToolCallId() != null || cp.getResumePayload() != null) {
            checkpointMapper.updateRuntimeRef(cp);
        }
        log.info("AgentRuntime: checkpoint 已落库 conversationId={} formToken={} action={}",
            conversation.getId(), cp.getFormToken(), action);
        return cp.getFormToken();
    }

    /**
     * HITL 通用模板（message 事件照发；兼容现状调用）。
     *
     * @return 方案文本（即本轮最后一条 message 内容，供调用方落库）
     */
    public String runHITLStage(OrchestrationRequest req, String workflowTitle, StagePlan plan) {
        return runHITLStage(req, workflowTitle, plan, false, null);
    }

    public String runHITLStage(OrchestrationRequest req, String workflowTitle, StagePlan plan,
                               Consumer<AgentCheckpoint> customizer) {
        return runHITLStage(req, workflowTitle, plan, false, customizer);
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
        return runHITLStage(req, workflowTitle, plan, skipMessage, null);
    }

    public String runHITLStage(OrchestrationRequest req, String workflowTitle, StagePlan plan,
                               boolean skipMessage, Consumer<AgentCheckpoint> customizer) {
        checkInterrupted(req); // 打断：不再弹出确认卡片/落库 checkpoint
        if (workflowTitle != null) {
            sendWorkflowEvent(req, "hitl_plan", workflowTitle, "node_started");
        }
        if (!skipMessage) {
            sendEvent(req, "message", Map.of("content", plan.planText()));
        }
        String formToken = createCheckpoint(req.getConversation(), plan.action(),
                enrichMaterialContext(req, plan.planPayload()), STEP_EXECUTE,
                plan.planText(), plan.actions(), plan.models(), plan.recommended(), plan.reasons(),
                plan.imageModels(), plan.videoModels(), plan.assets(), customizer);
        // 落库 assistant HITL 卡片消息（metadata 保留 kind/formContent/actions/formToken/expirationTime）
        String hitlMessageId = persistHitlCard(req, plan, formToken);
        if (hitlMessageId != null && !hitlMessageId.isBlank()) {
            req.setHitlPaused(true);
            req.setHitlMessageId(hitlMessageId);
            attachHitlMessageIdToPlan(formToken, hitlMessageId);
        } else {
            // 卡片落库失败时仍允许本轮正常收尾，避免 HITL 暂停后没有任何 assistant 消息可恢复。
            req.setHitlPaused(false);
            req.setHitlMessageId("");
        }
        Map<String, Object> event = new java.util.LinkedHashMap<>(Map.of(
            "formToken", formToken, "taskId", "",
            "formContent", plan.planText(),
            "actions", plan.actions(),
            "expirationTime", OffsetDateTime.now().plus(CHECKPOINT_TTL).toString(),
            "messageId", hitlMessageId));
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

    /** 落库一条 assistant HITL 卡片消息，metadata 保留 kind/formContent/actions/formToken/expirationTime；返回消息 id。 */
    private String persistHitlCard(OrchestrationRequest req, StagePlan plan, String formToken) {
        try {
            AgentMessage m = new AgentMessage();
            m.setConversationId(req.getConversation().getId());
            m.setRole("assistant");
            m.setContent(plan.planText());
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("kind", "video_plan".equals(plan.eventName()) ? "video-plan" : "hitl");
            metadata.put("pending", true);
            metadata.put("formContent", plan.planText());
            metadata.put("hitlType", plan.eventName());
            if (plan.actions() != null) metadata.put("actions", plan.actions());
            metadata.put("formToken", formToken);
            metadata.put("expirationTime", OffsetDateTime.now().plus(CHECKPOINT_TTL).toString());
            Map<String, Object> hitl = new java.util.LinkedHashMap<>();
            hitl.put("formContent", plan.planText());
            if (plan.actions() != null) hitl.put("actions", plan.actions());
            hitl.put("pending", true);
            hitl.put("formToken", formToken);
            hitl.put("expirationTime", OffsetDateTime.now().plus(CHECKPOINT_TTL).toString());
            metadata.put("hitl", hitl);
            m.setMetadata(metadata);
            messageMapper.insert(m);
            hitl.put("messageId", m.getId());
            metadata.put("hitl", hitl);
            m.setMetadata(metadata);
            messageMapper.updateById(m);
            if ("video_plan".equals(plan.eventName())) {
                metadata.put("videoPlan", buildVideoPlanMetadata(req, plan, formToken, m.getId()));
                m.setMetadata(metadata);
                messageMapper.updateById(m);
            }
            log.info("AgentRuntime: HITL 卡片已落库 assistant 消息 conversationId={} messageId={} formToken={}",
                    req.getConversation().getId(), m.getId(), formToken);
            return m.getId();
        } catch (Exception e) {
            log.warn("AgentRuntime: HITL 卡片 assistant 消息落库失败 conversationId={} error={}",
                    req.getConversation().getId(), e.getMessage());
            return "";
        }
    }

    /** checkpoint plan 记录 `_hitlMessageId`，供表单提交后按消息 id 更新该行 metadata。 */
    private void attachHitlMessageIdToPlan(String formToken, String messageId) {
        if (formToken == null || formToken.isBlank() || messageId == null || messageId.isBlank()) return;
        try {
            AgentCheckpoint cp = checkpointMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentCheckpoint>()
                            .eq(AgentCheckpoint::getFormToken, formToken)
                            .last("LIMIT 1"));
            if (cp == null || cp.getPlan() == null || cp.getPlan().isBlank()) return;
            Map<String, Object> plan = objectMapper.readValue(cp.getPlan(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (plan == null) return;
            plan.put("_hitlMessageId", messageId);
            cp.setPlan(objectMapper.writeValueAsString(plan));
            checkpointMapper.updateById(cp);
        } catch (Exception e) {
            log.warn("AgentRuntime: checkpoint plan 附加 _hitlMessageId 失败 formToken={} error={}", formToken, e.getMessage());
        }
    }

    /** 供 AgentChatService 在表单提交前置位时更新 HITL 卡片 metadata（按 formToken 查 `_hitlMessageId`）。 */
    public void markHitlCardSubmitted(String conversationId, String formToken, String action, String customText,
                                      Map<String, String> params, java.util.List<String> assetIds) {
        try {
            AgentCheckpoint cp = checkpointMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentCheckpoint>()
                            .eq(AgentCheckpoint::getFormToken, formToken)
                            .eq(AgentCheckpoint::getConversationId, conversationId)
                            .last("LIMIT 1"));
            String messageId = null;
            if (cp != null && cp.getPlan() != null && !cp.getPlan().isBlank()) {
                messageId = planField(cp.getPlan(), "_hitlMessageId");
            }
            if (messageId == null || messageId.isBlank()) return;
            String selectedTitle = planActionTitle(cp == null ? null : cp.getPlan(), action);
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("selectedActionId", action == null ? "" : action);
            metadata.put("selectedActionTitle", selectedTitle);
            metadata.put("selectedCustomText", customText == null ? "" : customText);
            metadata.put("selectedParams", params == null ? Map.of() : params);
            metadata.put("selectedAssetIds", assetIds == null ? List.of() : assetIds);
            metadata.put("submittedAt", OffsetDateTime.now().toString());
            // 用 updateById 走 JacksonTypeHandler 序列化 metadata（LambdaUpdateWrapper.set 不保证 apply typeHandler）
            AgentMessage msg = messageMapper.selectById(messageId);
            if (msg != null) {
                Map<String, Object> merged = msg.getMetadata() == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(msg.getMetadata());
                merged.putAll(metadata);
                merged.put("pending", false);
                Object oldHitl = merged.get("hitl");
                Map<String, Object> hitl = oldHitl instanceof Map<?, ?> old ? new java.util.LinkedHashMap<>((Map<String, Object>) old) : new java.util.LinkedHashMap<>();
                hitl.put("pending", false);
                hitl.putIfAbsent("formContent", merged.get("formContent"));
                hitl.putIfAbsent("actions", merged.get("actions"));
                hitl.putIfAbsent("formToken", merged.get("formToken"));
                hitl.putIfAbsent("expirationTime", merged.get("expirationTime"));
                hitl.putIfAbsent("messageId", messageId);
                hitl.put("selectedActionId", action == null ? "" : action);
                hitl.put("selectedActionTitle", selectedTitle);
                hitl.put("selectedCustomText", customText == null ? "" : customText);
                hitl.put("selectedParams", params == null ? Map.of() : params);
                hitl.put("selectedAssetIds", assetIds == null ? List.of() : assetIds);
                hitl.put("submittedAt", OffsetDateTime.now().toString());
                merged.put("hitl", hitl);
                msg.setMetadata(merged);
                messageMapper.updateById(msg);
            }
            log.info("AgentRuntime: HITL 卡片已标记提交 conversationId={} messageId={} action={}",
                    conversationId, messageId, action);
        } catch (Exception e) {
            log.warn("AgentRuntime: markHitlCardSubmitted 失败 conversationId={} formToken={} error={}",
                    conversationId, formToken, e.getMessage());
        }
    }

    /** 从 checkpoint 的固定/动态动作清单解析用户看到的标题；找不到回退静态映射。 */
    private String planActionTitle(String planJson, String action) {
        if (planJson == null || planJson.isBlank()) return actionTitle(action);
        try {
            var root = objectMapper.readTree(planJson);
            JsonNode actions = root.path("_actions");
            if (!actions.isArray() || actions.isEmpty()) {
                var items = root.path("items");
                actions = items.isArray() && !items.isEmpty() ? items.get(0).path("actions") : null;
            }
            if (actions != null && actions.isArray()) {
                for (var item : actions) {
                    if (action != null && action.equals(item.path("id").asText(""))) {
                        String title = item.path("title").asText("");
                        if (!title.isBlank()) return title;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析动作标题失败: {}", e.getMessage());
        }
        return actionTitle(action);
    }

    /** HITL 视频方案卡片的持久化字段，刷新后前端可恢复 VideoPlanCard。 */
    private Map<String, Object> buildVideoPlanMetadata(OrchestrationRequest req, StagePlan plan,
                                                        String formToken, String messageId) {
        String rawMessage = planPayloadString(plan, "message");
        Object rawDuration = planPayloadValue(plan, "duration");
        int duration = rawDuration instanceof Number n ? n.intValue() : 8;
        String message = rawMessage == null || rawMessage.isBlank() ? plan.planText() : rawMessage;
        String picUrl = planPayloadPicUrl(plan, req, "picUrl", "picUrls");
        Map<String, Object> video = new java.util.LinkedHashMap<>();
        video.put("formToken", formToken);
        video.put("messageId", messageId);
        video.put("message", message);
        video.put("duration", duration);
        video.put("picUrl", picUrl);
        video.put("actions", plan.actions() == null ? List.of() : plan.actions());
        if (plan.models() != null) video.put("models", plan.models());
        if (plan.recommended() != null) video.put("recommended", plan.recommended());
        if (plan.reasons() != null) video.put("reasons", plan.reasons());
        return video;
    }

    /** 从 planPayload[0] 或请求参考图中提取视频方案首图 URL。 */
    private String planPayloadPicUrl(StagePlan plan, OrchestrationRequest req, String directKey, String listKey) {
        String direct = planPayloadString(plan, directKey);
        if (direct != null && !direct.isBlank()) return direct;
        if (plan.planPayload() != null && !plan.planPayload().isEmpty()) {
            Object listValue = plan.planPayload().getFirst().get(listKey);
            if (listValue instanceof java.util.List<?> list && !list.isEmpty()) {
                String first = String.valueOf(list.getFirst());
                if (!first.isBlank()) return first;
            }
        }
        return req.getPicUrls() == null || req.getPicUrls().isEmpty() ? "" : req.getPicUrls().getFirst();
    }

    /** 读取 planPayload[0] 的字符串字段；缺失返回 null。 */
    private String planPayloadString(StagePlan plan, String key) {
        if (plan.planPayload() == null || plan.planPayload().isEmpty()) return null;
        Object value = plan.planPayload().getFirst().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 读取 planPayload[0] 的字段；缺失返回 null。 */
    private Object planPayloadValue(StagePlan plan, String key) {
        if (plan.planPayload() == null || plan.planPayload().isEmpty()) return null;
        return plan.planPayload().getFirst().get(key);
    }

    private String actionTitle(String action) {
        switch (action == null ? "" : action) {
            case "agree": return "满意";
            case "disagree": return "不满意";
            case "replace": return "先删除现有分镜再导入";
            case "append": return "追加到现有分镜后面";
            case "cancel": return "不生成分镜";
            case "generate_image": return "开始生成图片";
            case "generate_video": return "开始生成视频";
            case "custom": return "自定义输入";
            default: return action == null ? "" : action;
        }
    }

    /**
     * 所有 HITL 卡统一携带当前素材/资产上下文：把本轮已上传参考图（picUrls）与已确认资产（assetIds）
     * 合并进 checkpoint plan，resume 后不会丢失（用户上传了资产和提示词，下一张澄清卡仍能接力）。
     */
    static List<Map<String, Object>> enrichMaterialContext(OrchestrationRequest req,
                                                           List<Map<String, Object>> payload) {
        if (payload == null || payload.isEmpty()) return payload;
        List<String> picUrls = req.getPicUrls() == null ? List.of() : req.getPicUrls();
        List<String> assetIds = req.getAssetIds() == null ? List.of() : req.getAssetIds();
        if (picUrls.isEmpty() && assetIds.isEmpty()) return payload;
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> item : payload) {
            if (item == null) continue;
            Map<String, Object> copy = new java.util.LinkedHashMap<>(item);
            if (!picUrls.isEmpty() && !copy.containsKey("picUrls")) copy.put("picUrls", picUrls);
            if (!assetIds.isEmpty() && !copy.containsKey("assetIds")) copy.put("assetIds", assetIds);
            out.add(copy);
        }
        return out;
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
        String sheet = StoryboardClient.buildSheetText(assets);
        int maxChars = agentConfig.getHistoryContextMaxChars();
        return maxChars > 0 ? truncateText(sheet, maxChars) : sheet;
    }

    /** 勾选资产（逗号分隔 ID）的全部图片 URL（去重；人物多角度参考——2026-08-17 用户反馈
     *  纯文字描述生成的视频人物与资产完全不像，资产照片直接决定人物长相；无资产/无图返回空列表） */
    public List<String> assetImageUrls(String projectId, String assetIdsCsv) {
        if (projectId == null || assetIdsCsv == null || assetIdsCsv.isBlank()) return List.of();
        try {
            List<String> ids = java.util.Arrays.stream(assetIdsCsv.split(","))
                    .map(String::trim).filter(x -> !x.isBlank()).toList();
            if (ids.isEmpty()) return List.of();
            List<String> urls = new java.util.ArrayList<>();
            for (com.moon.moonagent.dto.response.AssetVO vo : storyboardClient.getProjectAssets(projectId)) {
                if (!ids.contains(vo.id())) continue;
                if (vo.images() != null) {
                    for (var img : vo.images()) {
                        if (img.url() != null && !img.url().isBlank()) urls.add(img.url());
                    }
                }
            }
            return urls;
        } catch (Exception e) {
            log.warn("获取资产图片失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 勾选资产的第一张图片 URL（单图兼容；取 assetImageUrls 首图）。无资产/无图返回空串 */
    public String firstAssetImageUrl(String projectId, String assetIdsCsv) {
        List<String> urls = assetImageUrls(projectId, assetIdsCsv);
        return urls.isEmpty() ? "" : urls.getFirst();
    }

    /** 会话内用户上传参考素材的 URL → purpose（agent_assets.purpose；无/读取失败返回空 Map） */
    public Map<String, String> referenceMaterialPurposes(String conversationId) {
        return referenceMaterialField(conversationId, true);
    }

    /** 会话内用户上传参考素材的 URL → kind（agent_assets.kind；无/读取失败返回空 Map） */
    public Map<String, String> referenceMaterialKinds(String conversationId) {
        return referenceMaterialField(conversationId, false);
    }

    /** 会话内用户上传过的参考图 URL（按上传顺序，最多 9 张）；前端漏传时后端找回用。 */
    public List<String> referenceMaterialUrls(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        try {
            List<AgentAsset> assets = assetMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentAsset>()
                            .eq(AgentAsset::getConversationId, conversationId)
                            .eq(AgentAsset::getType, "reference")
                            .orderByAsc(AgentAsset::getCreatedAt)
                            .last("LIMIT 9"));
            if (assets == null || assets.isEmpty()) return List.of();
            return assets.stream().map(AgentAsset::getUrl)
                    .filter(u -> u != null && !u.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("读取会话参考图失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, String> referenceMaterialField(String conversationId, boolean purpose) {
        if (conversationId == null || conversationId.isBlank()) return Map.of();
        try {
            List<AgentAsset> assets = assetMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentAsset>()
                            .eq(AgentAsset::getConversationId, conversationId)
                            .eq(AgentAsset::getType, "reference")
                            .orderByAsc(AgentAsset::getCreatedAt));
            if (assets == null || assets.isEmpty()) return Map.of();
            Map<String, String> out = new java.util.HashMap<>();
            for (AgentAsset a : assets) {
                if (a.getUrl() == null || a.getUrl().isBlank()) continue;
                out.put(a.getUrl(), purpose ? (a.getPurpose() == null ? "" : a.getPurpose())
                        : (a.getKind() == null || a.getKind().isBlank() ? "reference" : a.getKind()));
            }
            return out;
        } catch (Exception e) {
            log.warn("读取参考素材用途/类型失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 当前会话最近一条未过期 pending checkpoint 的 action；无则返回空串。 */
    public String pendingCheckpointAction(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return "";
        try {
            AgentCheckpoint cp = checkpointMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentCheckpoint>()
                            .eq(AgentCheckpoint::getConversationId, conversationId)
                            .eq(AgentCheckpoint::getStatus, "pending")
                            .orderByDesc(AgentCheckpoint::getCreatedAt)
                            .last("LIMIT 1"));
            return cp == null || cp.getAction() == null ? "" : cp.getAction();
        } catch (Exception e) {
            log.warn("读取待处理 checkpoint 失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 会话历史上下文文本（最近 max 条消息，按时间正序），供各编排 LLM 调用拼接——
     * 用户本条消息可能只有「重新生成/继续」等简短指令，真实需求在历史消息里，
     * 不读上下文会误判（如门禁判定资产不相关、方案生成跑偏）。读取失败/无消息返回空串。
     *
     * <p>单条消息与总量均受 {@code ai.agent.history-message-max-chars} /
     * {@code ai.agent.history-context-max-chars} 软限制；按最新消息优先保留，超预算的旧消息不再拼接。
     */
    public String historyContext(String conversationId, int max) {
        if (conversationId == null || conversationId.isBlank() || max <= 0) return "";
        try {
            List<com.moon.moonagent.entity.AgentMessage> msgs = messageMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.moon.moonagent.entity.AgentMessage>()
                            .eq(com.moon.moonagent.entity.AgentMessage::getConversationId, conversationId)
                            .orderByDesc(com.moon.moonagent.entity.AgentMessage::getCreatedAt)
                            .last("LIMIT " + max));
            return renderHistoryContext(msgs,
                    agentConfig.getHistoryMessageMaxChars(),
                    agentConfig.getHistoryContextMaxChars());
        } catch (Exception e) {
            log.warn("会话历史上下文读取失败: {}", e.getMessage());
            return "";
        }
    }

    /** 历史上下文渲染：msgs 按时间倒序（最新在前），输出按时间正序；包可见供预算裁剪测试。 */
    static String renderHistoryContext(List<com.moon.moonagent.entity.AgentMessage> msgs,
                                       int messageMaxChars, int contextMaxChars) {
        if (msgs == null || msgs.isEmpty()) return "";
        String header = "\n\n【最近对话上下文（用于理解完整需求，按时间顺序）】\n";
        int remaining = contextMaxChars <= 0 ? Integer.MAX_VALUE : contextMaxChars - header.length();
        if (remaining <= 0) return "";
        List<String> lines = new java.util.ArrayList<>();
        for (com.moon.moonagent.entity.AgentMessage m : msgs) {
            String content = m.getContent() == null ? "" : m.getContent().trim();
            if (content.isBlank()) continue;
            if (messageMaxChars > 0) content = truncateText(content, messageMaxChars);
            String prefix = "user".equals(m.getRole()) ? "用户：" : "助手：";
            int room = remaining - prefix.length() - 1;
            if (room <= 0) break;
            if (content.length() > room) content = truncateText(content, room);
            String line = prefix + content + "\n";
            lines.add(line);
            remaining -= line.length();
        }
        if (lines.isEmpty()) return "";
        java.util.Collections.reverse(lines);
        return header + String.join("", lines);
    }

    /** 字符软裁剪：maxChars<=0 不限制；超长时保留前部并用省略号占满最后一个字符位。 */
    static String truncateText(String text, int maxChars) {
        if (text == null || text.isEmpty() || maxChars <= 0 || text.length() <= maxChars) return text;
        if (maxChars == 1) return "…";
        return text.substring(0, maxChars - 1) + "…";
    }

    /**
     * 关联性门禁：提示词 × 勾选资产 判定。
     *
     * @param source 来源链标识（aisplit / video，存入 checkpoint plan 供 resume 分派）
     * @param picUrls video 链参考图列表（存 plan 供澄清后继续设计方案用；aisplit 传空列表）
     * @return null=放行（相关/判定失败/无资产）；非 null=本轮已结束（弱关联澄清卡片已发），调用方直接 return
     */
    public String runAssetGate(OrchestrationRequest req, String prompt, List<AssetVO> chosenAssets, String source, java.util.List<String> picUrls) {
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
                        "picUrls", picUrls == null ? List.of() : picUrls)),
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
        String originPrompt = planField(cp.getPlan(), PlanField.CONTENT);
        List<String> ids = parseAssetIds(planField(cp.getPlan(), PlanField.ASSET_IDS));
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
                        List.of(Map.of("source", planField(cp.getPlan(), PlanField.SOURCE),
                                "content", newPrompt, "assetIds", String.join(",", ids),
                                "picUrls", planPicUrls(cp.getPlan()),
                                "picUrl", planField(cp.getPlan(), PlanField.PIC_URL))),
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
        checkInterrupted(req); // 打断：不再发送生成结果/confirm_result，跳过被截断内容的落库
        if (workflowTitle != null) {
            sendWorkflowEvent(req, "resume_action", workflowTitle, "node_started");
        }
        String content = String.valueOf(result.getOrDefault("content", ""));
        sendMessage(req, content);
        @SuppressWarnings("unchecked")
        Map<String, Object> confirm = (Map<String, Object>) result.getOrDefault("confirm", Map.of());
        sendEvent(req, "confirm_result", confirm);
        long sceneCount = result.get("sceneCount") instanceof Number n ? n.longValue() : -1L;
        Map<String, Object> endPayload = new java.util.LinkedHashMap<>(Map.of(
                "messageId", "", "sceneCount", sceneCount, "content", content));
        if (result.get("suggestedActions") != null) {
            endPayload.put("suggestedActions", result.get("suggestedActions"));
        }
        sendEvent(req, "message_end", endPayload);
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
                                            String aspectRatio, java.util.List<String> referenceImages) {
        return startVideoGenerationAsync(req, prompt, duration, aspectRatio, referenceImages, Map.of());
    }

    /** 带用户参数选择的异步视频生成（params 显式优先，未提供键回退原值/默认）；
     *  多参考图走 referenceImages（图生视频/资产多图投喂），generatedImageUrl 传 null（统一按参考图处理） */
    public String startVideoGenerationAsync(OrchestrationRequest req, String prompt, String duration,
                                            String aspectRatio, java.util.List<String> referenceImages, Map<String, String> params) {
        return startVideoGenerationAsync(req, prompt, duration, aspectRatio, referenceImages,
                java.util.List.of(), params);
    }

    /** 多模态参考视频版本；图片仍走带角色的 referenceImages。 */
    public String startVideoGenerationAsync(OrchestrationRequest req, String prompt, String duration,
                                            String aspectRatio, java.util.List<String> referenceImages,
                                            java.util.List<String> referenceVideos, Map<String, String> params) {
        checkInterrupted(req); // 打断：不再创建视频任务
        String model = params.getOrDefault("model", null);
        String resolution = params.getOrDefault("resolution", null);
        String effDuration = params.getOrDefault("duration", duration);
        String effAspectRatio = params.getOrDefault("aspectRatio", aspectRatio);
        VideoGenerationPolicy.requireDirectAllowed(
                effDuration, req == null ? null : req.getContent());
        List<com.moon.moonagent.ai.video.ReferenceImage> roleImages =
                (referenceImages == null ? List.<String>of() : referenceImages).stream()
                        .map(com.moon.moonagent.ai.video.ReferenceImage::ofReference)
                        .toList();
        String taskId = generationService.createVideoTaskWithRole(
                req.getConversation(), null, prompt, model, resolution, null,
                effAspectRatio, effDuration, null,
                roleImages, null,
                referenceVideos == null ? List.of() : referenceVideos, true);
        if (taskId == null || taskId.isBlank()) {
            sendFriendlyError(req, null, "视频任务暂时创建不了，请稍后重试。");
            return null;
        }
        // 立即受理：task_accepted → 本轮 SSE 结束（前端转轮询，不再同步阻塞 7.5min）
        sendEvent(req, "task_accepted", Map.of(
                "taskId", taskId,
                "taskKind", "video",
                "message", "视频任务已受理，正在排队生成"));
        scheduleVideoPolling(taskId);
        return taskId;
    }

    /** 幂等启动视频轮询；重复调用不会为同一 taskId 创建多个后台循环。 */
    public void scheduleVideoPolling(String taskId) {
        if (taskId == null || taskId.isBlank() || !activeVideoPolls.add(taskId)) return;
        agentExecutor.submit(() -> {
            try {
                pollVideoTaskAndUpdate(taskId);
            } finally {
                activeVideoPolls.remove(taskId);
            }
        });
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

    /**
     * 带 ReferenceImage role 的异步视频生成。
     * 每个参考图可指定用途（首帧/尾帧/参考图），由适配器负责转换为各模型特定格式。
     */
    public String startVideoGenerationAsyncWithRole(OrchestrationRequest req, String prompt, String duration,
                                                    String aspectRatio, java.util.List<com.moon.moonagent.ai.video.ReferenceImage> referenceImages,
                                                    Map<String, String> params) {
        return startVideoGenerationAsyncWithRole(req, prompt, duration, aspectRatio, referenceImages,
                java.util.List.of(), params);
    }

    /** 带 ReferenceImage role + 多模态参考视频的异步视频生成。 */
    public String startVideoGenerationAsyncWithRole(OrchestrationRequest req, String prompt, String duration,
                                                    String aspectRatio,
                                                    java.util.List<com.moon.moonagent.ai.video.ReferenceImage> referenceImages,
                                                    java.util.List<String> referenceVideos,
                                                    Map<String, String> params) {
        return startVideoGenerationAsyncWithRoleInternal(
                req, prompt, duration, aspectRatio, referenceImages, referenceVideos, params, false);
    }

    /** 已进入分镜流程的单镜视频：允许原剧情总时长超过 15 秒，但本镜仍不得超过 15 秒。 */
    public String startStoryboardShotVideoGenerationAsyncWithRole(
            OrchestrationRequest req,
            String prompt,
            String duration,
            String aspectRatio,
            java.util.List<com.moon.moonagent.ai.video.ReferenceImage> referenceImages,
            java.util.List<String> referenceVideos,
            Map<String, String> params) {
        return startVideoGenerationAsyncWithRoleInternal(
                req, prompt, duration, aspectRatio, referenceImages, referenceVideos, params, true);
    }

    private String startVideoGenerationAsyncWithRoleInternal(
            OrchestrationRequest req,
            String prompt,
            String duration,
            String aspectRatio,
            java.util.List<com.moon.moonagent.ai.video.ReferenceImage> referenceImages,
            java.util.List<String> referenceVideos,
            Map<String, String> params,
            boolean storyboardShot) {
        checkInterrupted(req);
        Map<String, String> p = params == null ? Map.of() : params;
        String model = p.getOrDefault("model", null);
        String resolution = p.getOrDefault("resolution", null);
        String effDuration = p.getOrDefault("duration", duration);
        String effAspectRatio = p.getOrDefault("aspectRatio", aspectRatio);
        if (storyboardShot) {
            VideoGenerationPolicy.requireStoryboardShotAllowed(effDuration);
        } else {
            VideoGenerationPolicy.requireDirectAllowed(
                    effDuration, req == null ? null : req.getContent());
        }
        String taskId = generationService.createVideoTaskWithRole(
                req.getConversation(), null, prompt, model, resolution, null,
                effAspectRatio, effDuration, null,
                referenceImages == null ? List.of() : referenceImages, null,
                referenceVideos == null ? List.of() : referenceVideos, true);
        if (taskId == null || taskId.isBlank()) {
            sendFriendlyError(req, null, "视频任务暂时创建不了，请稍后重试。");
            return null;
        }
        sendEvent(req, "task_accepted", Map.of(
                "taskId", taskId,
                "taskKind", "video",
                "message", "视频任务已受理，正在排队生成"));
        scheduleVideoPolling(taskId);
        return taskId;
    }

    /** 按 taskId 更新 agent_assets 行（幂等；行不存在忽略——scene 视频无资产行）。
     *  completed 时把视频 URL 追加为 assistant 消息（![视频](url)），让视频持久到消息历史——
     *  否则 URL 只存 agent_assets，MessageBubble 从消息文本抠不到，刷新/切会话后不显示。 */
    private void updateVideoAsset(String taskId, String status, String url, String error) {
        try {
            AgentAsset asset = assetMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentAsset>()
                            .eq(AgentAsset::getTaskId, taskId).last("LIMIT 1"));
            if (asset == null) return;
            String previousStatus = asset.getStatus();
            asset.setStatus(status);
            if (url != null && !url.isBlank()) asset.setUrl(url);
            if (error != null && !error.isBlank()) asset.setError(error);
            if ("completed".equals(status) && url != null && !url.isBlank()) {
                asset.setResultUrls(org.springframework.util.StringUtils.hasText(asset.getResultUrls())
                        ? asset.getResultUrls()
                        : "[\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]");
            }
            if ("completed".equals(status) || "failed".equals(status)) {
                if (asset.getCreatedAt() != null) {
                    asset.setDurationMs(java.time.Duration.between(asset.getCreatedAt(), java.time.OffsetDateTime.now()).toMillis());
                }
            }
            assetMapper.updateById(asset);
            if ("completed".equals(status) && url != null && !url.isBlank()) {
                persistVideoMessage(asset, url);
            }
            if ((!status.equals(previousStatus))
                    && ("completed".equals(status) || "failed".equals(status))
                    && asset.getPlanId() != null && !asset.getPlanId().isBlank()
                    && taskEventPublisher != null) {
                taskEventPublisher.publishEvent(new com.moon.moonagent.ai.agent.orchestration.TaskAssetTerminalEvent(
                        asset.getConversationId(), asset.getPlanId(), asset.getNodeId(),
                        "completed".equals(status), "completed".equals(status) ? url : error));
            }
        } catch (Exception e) {
            log.warn("视频资产状态更新失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    /** 视频生成完成：往 agent_messages 追加一条 assistant 消息（content=磁盘视频 URL），
     *  让视频持久到消息历史（刷新/切会话后 MessageBubble 仍能渲染）。
     *  用裸 .mp4 URL 而非 ![视频](url)：MessageBubble 的 imgMatch 先于 videoMatch，![视频](url.mp4) 会被
     *  imgMatch 截走渲染成 <img>（mp4 裂图）；裸 URL 只匹配 videoMatch → 正确渲染 <video>。
     *  幂等：按 taskId 防重复——同一资产行不会连续 completed（轮询终态只回一次）。 */
    private void persistVideoMessage(AgentAsset asset, String url) {
        try {
            if (asset.getConversationId() == null || asset.getConversationId().isBlank()) return;
            AgentMessage msg = new AgentMessage();
            msg.setConversationId(asset.getConversationId());
            msg.setRole("assistant");
            msg.setContent(url);
            messageMapper.insert(msg);
            log.info("视频生成完成，已追加消息历史: conversationId={}, url={}", asset.getConversationId(), url);
        } catch (Exception e) {
            log.warn("视频消息追加失败: conversationId={}, error={}", asset.getConversationId(), e.getMessage());
        }
    }

    /** checkpoint plan JSON 取字段（宽松解析，缺失返回空串） */
    public String planField(String planJson, String field) {
        if (planJson == null || planJson.isBlank()) return "";
        try {
            var root = objectMapper.readTree(planJson);
            if (root.has(field)) {
                return root.path(field).asText("");
            }
            var items = root.path("items");
            if (items.isArray() && !items.isEmpty()) {
                return items.get(0).path(field).asText("");
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /** checkpoint plan 恢复勾选资产 ID：读取 items[0].assetIds（字符串逗号或 JSON 数组），缺失返回空列表。 */
    public static List<String> planAssetIds(String planJson) {
        if (planJson == null || planJson.isBlank()) return List.of();
        try {
            var root = new ObjectMapper().readTree(planJson);
            var items = root.path("items");
            var node = items.isArray() && !items.isEmpty() ? items.get(0) : root;
            JsonNode assetNode = node.path("assetIds");
            if (assetNode.isArray()) {
                List<String> out = new java.util.ArrayList<>();
                for (JsonNode item : assetNode) {
                    String v = item.asText("");
                    if (!v.isBlank()) out.add(v);
                }
                return out;
            }
            if (assetNode.isTextual() && !assetNode.asText().isBlank()) {
                return java.util.Arrays.stream(assetNode.asText().split(","))
                        .map(String::trim)
                        .filter(v -> !v.isEmpty())
                        .toList();
            }
        } catch (Exception e) {
            log.warn("解析 checkpoint 资产 ID 失败: {}", e.getMessage());
        }
        return List.of();
    }

    /** 枚举重载：planField(planJson, PlanField.CONTENT) */
    public String planField(String planJson, PlanField field) {
        return planField(planJson, field.key());
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

    /** checkpoint plan JSON 取参考图 URL 列表（宽松解析，缺失/失败返回空 list）。
     *  优先 picUrls 数组（新格式）；回退 picUrl / imageUrl 单图（旧格式）。
     *  注意：绝不回退 source 字段——source 在本链路是来源链标识(video/aisplit)，不是图片 URL，
     *  回退会把 "video" 当成参考图 URL 传给生视频，导致参考图丢失（2026-08-28 实机借图失败根因）。 */
    public List<String> planPicUrls(String planJson) {
        if (planJson == null || planJson.isBlank()) return List.of();
        try {
            var root = objectMapper.readTree(planJson);
            var items = root.path("items");
            var node = (items.isArray() && !items.isEmpty()) ? items.get(0) : root;
            // 1. picUrls 数组（新格式）
            if (node.has("picUrls") && node.get("picUrls").isArray()) {
                List<String> urls = new java.util.ArrayList<>();
                for (var u : node.get("picUrls")) {
                    if (u.isNumber() || u.isTextual()) {
                        String s = u.asText("");
                        if (!s.isBlank()) urls.add(s);
                    } else if (u.isObject() && u.has("url")) {
                        String s = u.get("url").asText("");
                        if (!s.isBlank()) urls.add(s);
                    }
                }
                if (!urls.isEmpty()) return urls;
            }
            // 2. 回退单图：picUrl / imageUrl（旧格式，仅这两种是真图片 URL）
            String single = node.path("picUrl").asText("");
            if (single.isBlank()) single = node.path("imageUrl").asText("");
            return single.isBlank() ? List.of() : List.of(single);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** checkpoint plan 的 LLM 推荐参数（_recommended）；缺失/失败返回空 Map。 */
    public Map<String, String> planRecommendedParams(String planJson) {
        if (planJson == null || planJson.isBlank()) return Map.of();
        try {
            var node = objectMapper.readTree(planJson).path("_recommended");
            if (node.isObject()) {
                Map<String, String> out = new java.util.LinkedHashMap<>();
                node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
                return out;
            }
        } catch (Exception ignored) {}
        return Map.of();
    }

    /** checkpoint plan JSON 取 sources 列表（video-role-select 专用）。
     *  与 planPicUrls 类似，但读取 "sources" key（video-role-select checkpoint 使用）。 */
    public List<String> planSources(String planJson) {
        if (planJson == null || planJson.isBlank()) return List.of();
        try {
            var root = objectMapper.readTree(planJson);
            var items = root.path("items");
            var node = (items.isArray() && !items.isEmpty()) ? items.get(0) : root;
            if (node.has("sources") && node.get("sources").isArray()) {
                List<String> urls = new java.util.ArrayList<>();
                for (var u : node.get("sources")) {
                    if (u.isTextual()) {
                        String s = u.asText("");
                        if (!s.isBlank()) urls.add(s);
                    }
                }
                return urls;
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 从 plan JSON 的 items[0] 读取字符串数组字段（缺失/坏数据返回空列表）。 */
    public List<String> planStringList(String planJson, String field) {
        if (planJson == null || planJson.isBlank() || field == null || field.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(planJson);
            JsonNode node = root.path("items").isArray() && !root.path("items").isEmpty()
                    ? root.path("items").get(0).path(field)
                    : root.path(field);
            if (!node.isArray()) return List.of();
            List<String> result = new java.util.ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("");
                if (!value.isBlank()) result.add(value);
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
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
                Integer duration = null;
                var durationNode = it.path("duration");
                if (durationNode.isInt() || durationNode.isLong()) {
                    duration = durationNode.asInt();
                } else if (durationNode.isTextual()) {
                    try {
                        duration = Integer.parseInt(durationNode.asText("").replaceAll("[^0-9-]", ""));
                    } catch (NumberFormatException ignored) {
                        duration = null;
                    }
                }
                result.add(new AgentSceneItem(
                    it.path("sceneNumber").asInt(0),
                    it.path("scriptContent").asText(""),
                    it.path("imagePrompt").asText(""),
                    it.path("videoPrompt").asText(""),
                    it.path("negativePrompt").asText(""),
                    it.path("cameraMovement").asText(""),
                    it.path("shotType").asText(""),
                    it.path("soundDesign").asText(""),
                    duration));
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
            String content = planClient().prompt().options(planOptions())
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
            String content = planClient().prompt().options(planOptions())
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

    /** SSE data 统一序列化为 JSON 字符串（SSE 规范 data 为文本；避免经 api-gateway 转发时无 text/event-stream converter 崩溃） */
    private static final ObjectMapper SSE_JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    public static String sseJson(Object data) {
        try { return SSE_JSON.writeValueAsString(data); }
        catch (Exception e) { return String.valueOf(data); }
    }

    /** 发送带稳定阶段标识的 workflow 事件；durationMs 仅在节点结束时写入。 */
    public void sendWorkflowEvent(OrchestrationRequest req, String stage, String title, String status) {
        sendWorkflowEvent(req, stage, title, status, null);
    }

    /** 发送阶段事件。startedAtNanos 由 {@link #workflowStart()} 获取，结束事件自动计算耗时。 */
    public void sendWorkflowEvent(OrchestrationRequest req, String stage, String title, String status,
                                  Long startedAtNanos) {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("stage", stage);
        data.put("title", title == null ? "" : title);
        data.put("status", status);
        if (startedAtNanos != null) {
            data.put("durationMs", Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L));
        }
        sendEvent(req, "workflow", data);
    }

    /** 统一阶段计时起点。 */
    public static long workflowStart() {
        return System.nanoTime();
    }

    /** 执行同步阶段：自动发送 started，成功发送 finished，异常发送 failed 后原样抛出。 */
    public <T> T runWorkflowStage(OrchestrationRequest req, String stage, String title,
                                  java.util.function.Supplier<T> action) {
        long startedAtNanos = workflowStart();
        sendWorkflowEvent(req, stage, title, "node_started");
        try {
            T result = action.get();
            sendWorkflowEvent(req, stage, title, "node_finished", startedAtNanos);
            return result;
        } catch (RuntimeException | Error e) {
            sendWorkflowEvent(req, stage, title, "node_failed", startedAtNanos);
            throw e;
        }
    }

    /** SseEmitter 事件发送（前端断开忽略）；message 事件同步更新 req.lastMessage（per-request，并发安全） */
    public void sendEvent(OrchestrationRequest req, String eventName, Map<String, Object> data) {
        if (req != null && req.isNativeEventsOnly()) {
            if ("message".equals(eventName) && data.get("content") != null) {
                req.setLastMessage(String.valueOf(data.get("content")));
            }
            return;
        }
        if ("message_end".equals(eventName)) {
            Map<String, Object> enriched = new java.util.LinkedHashMap<>(data);
            if (req.getTrace() != null && !req.getTrace().getTurnMetadata().isEmpty()) {
                enriched.putAll(req.getTrace().getTurnMetadata());
            }
            if (req.getTurnMetadata() != null && !req.getTurnMetadata().isEmpty()) {
                enriched.putAll(req.getTurnMetadata());
            }
            if (!enriched.containsKey("reasoning")) {
                Object reasoning = req.getReasoning() != null ? req.getReasoning()
                        : req.getTrace() != null ? req.getTrace().getReasoning() : null;
                if (reasoning != null) enriched.put("reasoning", reasoning);
            }
            if (!enriched.containsKey("agentTrace") && req.getTrace() != null) {
                java.util.List<com.moon.moonagent.ai.agent.AgentTraceEvent> events = req.getTrace().snapshot();
                if (!events.isEmpty()) enriched.put("agentTrace", events);
            }
            if (!enriched.containsKey("suggestedActions") && req.getSuggestedActions() != null
                    && !req.getSuggestedActions().isEmpty()) {
                enriched.put("suggestedActions", req.getSuggestedActions());
            }
            data = enriched;
        }
        if (("message_end".equals(eventName) || "task_accepted".equals(eventName))
                && req.getTrace() != null) {
            data = req.getTrace().enrichTerminalEvent(data);
        }
        if ("message".equals(eventName) && data.get("content") != null) {
            req.setLastMessage(String.valueOf(data.get("content")));
        }
        try {
            req.getEmitter().send(SseEmitter.event().name(eventName).data(sseJson(data)));
        } catch (Exception e) {
            log.debug("SseEmitter 发送失败（前端可能已断开）: event={}", eventName);
        }
    }

    /** 标记一次模型调用开始；失败且无 usage 时仍计入 modelCalls。 */
    public void beginModelCall(OrchestrationRequest req) {
        if (req != null && req.getTrace() != null) req.getTrace().beginModelCall();
    }

    /** 记录 Spring AI 响应 usage；同一次调用只应在拿到最终响应后调用一次。 */
    public void recordUsage(OrchestrationRequest req, ChatResponse response) {
        if (req != null && req.getTrace() != null) req.getTrace().recordUsage(response);
    }

    /** 记录另一套模型运行时的 usage。 */
    public void recordUsage(OrchestrationRequest req, long inputTokens, long outputTokens, long cachedInputTokens) {
        if (req != null && req.getTrace() != null) {
            req.getTrace().recordUsage(inputTokens, outputTokens, cachedInputTokens);
        }
    }

    /** 便捷转发：方案文本 → message 事件（流式增量时逐段更新 lastMessage） */
    public void sendMessage(OrchestrationRequest req, String content) {
        sendEvent(req, "message", Map.of("content", content));
    }

    // ===== 取消意图判定（分镜澄清死循环出口，2026-08-28） =====

    /** 取消意图规则词：命中即判定为「用户想终止当前分镜生成」。 */
    private static final java.util.regex.Pattern CANCEL_RULE = java.util.regex.Pattern.compile(
            "取消|不生成|算了|终止|停止|跳过|换个话题|换话题|不弄了|不做了|不分镜|不要了|不用了|算了不|撤消|撤销");

    /**
     * 判定一段用户文本是否为「取消/终止当前分镜生成」意图。
     * 两级：① 规则关键词命中 → true（快、准、零成本）；② 未命中 → 调一次 LLM 兜底判定
     * （覆盖规则没枚举到的表达），LLM 输出 JSON {@code {cancel:true|false}}。
     * LLM 调用失败/解析失败 → 返回 false（不误杀，宁可多问一轮）。规则优先，LLM 兜底。
     */
    public boolean isCancelIntent(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.trim();
        // ① 规则
        if (CANCEL_RULE.matcher(t).find()) {
            log.info("取消意图规则命中: text={}", t);
            return true;
        }
        // ② LLM 兜底
        try {
            String raw = retryTransient(() -> intentClient().prompt()
                    .options(intentOptions())
                    .system("你是意图判定助手。判断用户这句话是不是想【取消/终止当前的分镜生成操作】。"
                            + "只输出一个 JSON 对象 {\"cancel\": true 或 false}，不要任何其他文字。")
                    .user(t)
                    .call()
                    .content());
            if (raw == null || raw.isBlank()) return false;
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            String json = (a >= 0 && b > a) ? raw.substring(a, b + 1) : raw;
            return objectMapper.readTree(json).path("cancel").asBoolean(false);
        } catch (Exception e) {
            log.warn("取消意图 LLM 判定失败（按不取消处理）: {}", e.getMessage());
            return false;
        }
    }
}
