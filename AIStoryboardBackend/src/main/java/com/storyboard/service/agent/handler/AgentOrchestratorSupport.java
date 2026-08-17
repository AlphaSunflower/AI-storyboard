package com.storyboard.service.agent.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.Scene;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.AgentCheckpointMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.agent.AgentGenerationService;
import com.storyboard.service.agent.AgentSceneItem;
import com.storyboard.service.agent.AssetRelevanceResult;
import com.storyboard.service.ai.GatewayModelService;
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
 * 等 LLM 调用（复用网关默认视觉模型 deepseek-v4-flash，超时 120s）、HITL 通用模板
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
    private final com.storyboard.service.ai.AgentAiConfigProperties agentConfig;
    private final AgentGenerationService generationService;
    private final AgentAssetMapper assetMapper;
    private final GatewayModelService gatewayModelService;
    private final SceneMapper sceneMapper;
    private final com.storyboard.service.AssetService assetService;
    private final com.storyboard.service.agent.AssetMatchingService assetMatchingService;

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
                    "你是分镜助手，先理解用户的分镜需求并给出优化后的剧本。"
                        + "输出 JSON：{\"type\":1或0,\"message\":\"给用户的回复\",\"script\":\"优化后的完整剧本\",\"options\":[{\"id\":\"opt1\",\"title\":\"选项文案\"}]}"
                        + "。type=1 表示已理解可继续（此时 script 必填，options 为空数组）；"
                        + "type=0 表示关键信息缺失需追问（此时 message 只问一个最关键的问题，script 为空，options 必须给出 2~4 个选项供用户选择，title 用简短中文动词短语）；"
                        + "用户回复只要提供了任何有效信息（哪怕不完整），就直接用已有信息生成剧本（type=1），不要重复追问、不要一次问多个问题；"
                        + "只有回复为空或与需求完全无关时才 type=0。",
                    content, req);
            return new BeanOutputConverter<>(ScriptOptimizeResult.class).convert(raw);
        } catch (Exception e) {
            log.warn("剧本优化 LLM 调用失败: {}", e.getMessage());
            return new ScriptOptimizeResult(0, "已理解你的需求，请继续补充。", null, List.of());
        }
    }

    /** 分镜方案设计（message 字段流式增量转发） */
    public StoryboardPlanResult callStoryboardPlan(String script, OrchestrationRequest req) {
        try {
            String raw = streamPlanWithMessage(
                    "你是分镜方案设计师。基于剧本给出分镜方案要点。"
                        + "输出 JSON：{\"type\":1或0,\"message\":\"方案说明\",\"options\":[{\"id\":\"opt1\",\"title\":\"选项文案\"}]}"
                        + "。type=1 方案已明确（options 为空数组）；type=0 需用户补充（message 只问一个最关键的问题，options 必须给出 2~4 个选项供用户选择，title 用简短中文动词短语）；"
                        + "用户回复只要提供了任何有效信息就直接生成方案（type=1），不要重复追问。",
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
                .system("你是 AI 绘画提示词工程师。根据用户的需求输出一条高质量图片生成提示词，"
                    + "必须使用中文输出，只输出提示词文本本身，不要任何解释、引号或前后缀。")
                .user(content)
                .call()
                .content());
            return raw != null && !raw.isBlank() ? raw.trim() : content;
        } catch (Exception e) {
            log.warn("图片提示词 LLM 调用失败: {}", e.getMessage());
            return content;
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
                .system("你是图片修改方向设计师。基于当前图片方案，从不同修改维度给出 2~4 个具体可执行的修改方向选项。"
                    + (hasSource ? "注意图片有参考图（图改图场景），选项要能基于原图调整。" : "")
                    + "输出 JSON：{\"message\":\"给用户的简短引导语（≤20 字）\",\"options\":[{\"id\":\"opt1\",\"title\":\"维度-具体方向（≤10 字）\"}]}。"
                    + "title 用「维度-方向」格式，如 场景-婚礼现场、氛围-更浪漫、画风-Q版、服装-中式礼服。只输出 JSON。")
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
            String system = "你是视频生成方案设计师。根据用户需求设计视频 prompt。"
                    + "输出 JSON：{\"message\":\"视频生成 prompt，中文 50~120 字，描述动作/运镜/光线/氛围\",\"duration\":4~15 整数"
                    + (withParams ? ",\"params\":{\"model\":\"所选模型名\",\"resolution\":\"所选分辨率\",\"aspectRatio\":\"所选画幅\"},\"reasons\":{\"model\":\"推荐理由\",\"resolution\":\"推荐理由\",\"aspectRatio\":\"推荐理由\"}}" : "")
                    + "}"
                    + (withParams ? "。可用模型与参数选项：\n" + modelOptionsText
                        + "。从上述选项中为每个参数选择最合适的值，理由简短（≤15 字）。" : "")
                    + " 只输出 JSON。";
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

    /** 懒加载 planClient：对话交流统一 deepseek-v4-flash（用户指定；deepseek 无思考参数，不加 thinking_level） */
    private ChatClient planClient() {
        if (planClient == null) {
            synchronized (this) {
                if (planClient == null) {
                    planClient = chatClientBuilder
                        .defaultOptions(OpenAiChatOptions.builder()
                            .model("deepseek-v4-flash")
                            .timeout(Duration.ofSeconds(120)))
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
                .system("你是分镜生成参数推荐官。根据整套分镜剧情的整体风格与内容，从给定选项中选择一套适合整套分镜的图片生成参数和视频生成参数。"
                    + "必须严格从给定选项中取值，只输出 JSON："
                    + "{\"image\":{\"model\":\"生图模型\",\"size\":\"尺寸\",\"quality\":\"质量\"},"
                    + "\"video\":{\"model\":\"视频模型\",\"duration\":\"时长秒数\",\"resolution\":\"分辨率\",\"aspectRatio\":\"画幅\"},"
                    + "\"reasons\":{\"imageModel\":\"推荐理由(≤15字)\",\"imageSize\":\"理由\",\"imageQuality\":\"理由\","
                    + "\"videoModel\":\"理由\",\"videoDuration\":\"理由\",\"videoResolution\":\"理由\",\"videoAspectRatio\":\"理由\"}}。")
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
        LambdaUpdateWrapper<Scene> uw = new LambdaUpdateWrapper<Scene>().eq(Scene::getProjectId, projectId);
        putParam(uw, Scene::getImageModel, params.get("imageModel"));
        putParam(uw, Scene::getImageSize, params.get("imageSize"));
        putParam(uw, Scene::getImageQuality, params.get("imageQuality"));
        putParam(uw, Scene::getVideoModel, params.get("videoModel"));
        putParam(uw, Scene::getVideoResolution, params.get("videoResolution"));
        putParam(uw, Scene::getVideoAspectRatio, params.get("videoAspectRatio"));
        String d = params.get("videoDuration");
        if (d != null && !d.isBlank()) {
            try {
                uw.set(Scene::getDuration, Integer.parseInt(d.trim()));
            } catch (NumberFormatException ignored) {
                // 非法时长忽略
            }
        }
        // 无 set 语句（params 键全部不匹配/为空）→ 直接返回，避免生成无 SET 的非法 UPDATE 语句抛 SQL 异常
        //（曾导致 resume 写库分支中断、分镜资产自动关联未执行）
        String setSql = uw.getSqlSet();
        if (setSql == null || setSql.isBlank()) return;
        sceneMapper.update(null, uw);
    }

    private void putParam(LambdaUpdateWrapper<Scene> uw, SFunction<Scene, ?> col, String v) {
        if (v != null && !v.isBlank()) uw.set(col, v.trim());
    }

    /** 创建 HITL checkpoint（pending，30min 过期），返回 form_token */
    public String createCheckpoint(AgentConversation conversation, String action,
                                   List<Map<String, Object>> planPayload, String step) {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setConversationId(conversation.getId());
        cp.setAction(action);
        cp.setFormToken(UUID.randomUUID().toString());
        cp.setStep(step);
        cp.setStatus("pending");
        cp.setExpirationTime(OffsetDateTime.now().plus(CHECKPOINT_TTL));
        try {
            cp.setPlan(objectMapper.writeValueAsString(Map.of("items", planPayload)));
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
        String formToken = createCheckpoint(req.getConversation(), plan.action(), plan.planPayload(), STEP_EXECUTE);
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

    /** 勾选资产 → 设定集文字（委托 AssetService.buildSheetText；空列表返回空串） */
    public String assetSheetText(List<AssetVO> assets) {
        return assetService.buildSheetText(assets);
    }

    /**
     * 关联性门禁：提示词 × 勾选资产 判定。
     *
     * @param source 来源链标识（aisplit / video，存入 checkpoint plan 供 resume 分派）
     * @return null=放行（相关/判定失败/无资产）；非 null=本轮已结束（弱关联澄清卡片已发），调用方直接 return
     */
    public String runAssetGate(OrchestrationRequest req, String prompt, List<AssetVO> chosenAssets, String source) {
        if (chosenAssets == null || chosenAssets.isEmpty()) return null;
        AssetRelevanceResult r = assetMatchingService.judgeRelevance(prompt, chosenAssets);
        if (r.relevant()) return null;
        // 弱关联：澄清上限未达 → 弹卡片；已达 → 提示后放行（复用 clarifyLimitReached 的提示消息）
        if (clarifyLimitReached(req.getConversation().getId(), req)) return null;
        List<String> ids = chosenAssets.stream().map(AssetVO::id).toList();
        String planText = "⚠ 检测到你勾选的资产与当前需求关联性不强：\n"
                + assetService.buildSheetText(chosenAssets)
                + "\n" + (r.reason() == null || r.reason().isBlank() ? "请讲清楚这些资产在本次创作中的用途。" : r.reason())
                + "\n\n请重新描述需求（讲清楚资产如何融入），或选择不使用资产继续。";
        return runHITLStage(req, null, new StagePlan(
                planText, "asset-gate",
                List.of(Map.of("source", source, "content", prompt, "assetIds", String.join(",", ids))),
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
                                "content", newPrompt, "assetIds", String.join(",", ids))),
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
                if ("completed".equals(status)) {
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
    public List<com.storyboard.service.agent.SceneAssetMatch> parsePlanAssetMatches(String planJson) {
        if (planJson == null || planJson.isBlank()) return List.of();
        try {
            var root = objectMapper.readTree(planJson);
            var items = root.path("items");
            if (!items.isArray()) return List.of();
            List<com.storyboard.service.agent.SceneAssetMatch> result = new java.util.ArrayList<>();
            for (var it : items) {
                if (!it.has("assetIds") || !it.path("assetIds").isArray()) continue;
                List<String> ids = new java.util.ArrayList<>();
                for (var id : it.path("assetIds")) ids.add(id.asText(""));
                result.add(new com.storyboard.service.agent.SceneAssetMatch(
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
                    .system("你是用户友好的 AI 创作助手。上游 AI 生成服务返回了错误，请把错误翻译成对用户友好的中文回复：\n"
                            + "- 如果是内容安全审核拒绝（错误含 safety/rejected/violations/审核 等关键词）：告诉用户提示词可能触及了内容审核限制（如暴力等类别），建议修改措辞后重试，不要展开敏感细节。\n"
                            + "- 如果是网络/服务不可用（错误含 timeout/connect/refused/5xx/429 等）：告诉用户服务暂时繁忙，请稍后重试。\n"
                            + "- 其他错误：简短说明生成失败了，建议稍后重试或调整描述。\n"
                            + "要求：2~4 句自然口语中文，不要出现原始错误码或英文原文，不要提“上游/渠道”等技术词。")
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
                    .system("你是图片生成错误修复助手。用户提交的图片提示词被上游 AI 生成服务拒绝，请判断错误并处理：\n"
                            + "- 如果错误是内容安全审核拒绝（错误含 safety/rejected/violations/审核 等关键词）：重写提示词，"
                            + "保留用户原本的创作意图（主体/构图/氛围），剔除可能触发审核的元素（暴力/血腥等），用更温和的措辞表达。\n"
                            + "- 其他错误（网络/超时/服务不可用等）：不重写。\n"
                            + "只输出 JSON：{\\\"recoverable\\\": true/false, \\\"rewrittenPrompt\\\": \\\"重写后的提示词（不可恢复时为空串）\\\", "
                            + "\\\"message\\\": \\\"给用户看的简短中文说明（可恢复时如：你的描述可能触及暴力内容，我帮你调整了措辞重新生成；不可恢复时如：服务暂时繁忙，请稍后重试）\\\"}。"
                            + "禁止任何解释、代码块或多余字符。")
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
