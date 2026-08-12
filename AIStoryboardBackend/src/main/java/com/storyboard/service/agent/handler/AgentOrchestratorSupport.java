package com.storyboard.service.agent.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.entity.AgentConversation;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.AgentCheckpointMapper;
import com.storyboard.service.agent.AgentGenerationService;
import com.storyboard.service.agent.AgentSceneItem;
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

    /**
     * HITL 阶段产出（模板 {@link #runHITLStage} 的输入）：
     * 方案文本 + checkpoint action + plan 载荷 + 事件名（human_input / video_plan）+ 确认按钮
     * + models（网关模型列表含参数能力，卡片参数选择器用）+ recommended/reasons（LLM 推荐参数值与理由）。
     */
    public record StagePlan(String planText, String action, List<Map<String, Object>> planPayload,
                            String eventName, List<Map<String, Object>> actions,
                            List<Map<String, Object>> models,
                            Map<String, String> recommended,
                            Map<String, String> reasons) {
        /** 兼容构造器：无模型/推荐参数（现有 7 处调用零改动） */
        public StagePlan(String planText, String action, List<Map<String, Object>> planPayload,
                         String eventName, List<Map<String, Object>> actions) {
            this(planText, action, planPayload, eventName, actions, List.of(), Map.of(), Map.of());
        }
    }

    // ===== LLM 调用（结构化输出：纯解析，不发 response_format） =====

    /** 剧本优化：手动澄清循环的单步调用（message 字段流式增量转发） */
    public ScriptOptimizeResult callScriptOptimize(String content, OrchestrationRequest req) {
        try {
            String raw = streamPlanWithMessage(
                    "你是分镜助手，先理解用户的分镜需求并给出优化后的剧本。"
                        + "输出 JSON：{\"type\":1或0,\"message\":\"给用户的回复\",\"script\":\"优化后的完整剧本\",\"options\":[{\"id\":\"opt1\",\"title\":\"选项文案\"}]}"
                        + "。type=1 表示已理解可继续（此时 script 必填，options 为空数组）；"
                        + "type=0 表示需求不足需追问（此时 message 为追问内容，script 为空，options 必须给出 2~4 个选项供用户选择，title 用简短中文动词短语）。",
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
                        + "。type=1 方案已明确（options 为空数组）；type=0 需用户补充（message 为追问，options 必须给出 2~4 个选项供用户选择，title 用简短中文动词短语）。",
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
     * HITL 通用模板：workflow → 方案消息 → checkpoint 落库 → human_input/video_plan 事件
     * （发完即结束本轮，等表单提交 resume）。
     *
     * @return 方案文本（即本轮最后一条 message 内容，供调用方落库）
     */
    public String runHITLStage(OrchestrationRequest req, String workflowTitle, StagePlan plan) {
        if (workflowTitle != null) {
            sendEvent(req, "workflow", Map.of("title", workflowTitle, "status", "node_started"));
        }
        sendEvent(req, "message", Map.of("content", plan.planText()));
        String formToken = createCheckpoint(req.getConversation(), plan.action(), plan.planPayload(), STEP_EXECUTE);
        Map<String, Object> event = new java.util.LinkedHashMap<>(Map.of(
            "formToken", formToken, "taskId", "",
            "formContent", plan.planText(),
            "actions", plan.actions(),
            "expirationTime", OffsetDateTime.now().plus(CHECKPOINT_TTL).toString()));
        // 模型/参数选项与 LLM 推荐（非空才下发；旧前端/无配置时字段缺失，行为与现状一致）
        if (plan.models() != null && !plan.models().isEmpty()) event.put("models", plan.models());
        if (plan.recommended() != null && !plan.recommended().isEmpty()) event.put("recommended", plan.recommended());
        if (plan.reasons() != null && !plan.reasons().isEmpty()) event.put("reasons", plan.reasons());
        sendEvent(req, plan.eventName(), event);
        return plan.planText();
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
