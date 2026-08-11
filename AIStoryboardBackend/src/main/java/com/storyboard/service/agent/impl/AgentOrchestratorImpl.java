package com.storyboard.service.agent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import com.storyboard.mapper.AgentCheckpointMapper;
import com.storyboard.mapper.AgentMessageMapper;
import com.storyboard.service.agent.AgentOrchestrator;
import com.storyboard.service.agent.AgentSceneItem;
import com.storyboard.service.agent.AgentTools;
import com.storyboard.service.agent.IntentRecognitionService;
import com.storyboard.service.ai.AiConfigProperties;
import com.storyboard.service.ai.ScriptGenerationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编排状态机实现（应用层 bounded loop，混合循环模式）：
 *
 * <pre>
 * INTENT(意图识别) → 需求澄清(手动循环 LLM 逐轮确认) → HITL checkpoint(落库, 等人工)
 *   → resume(表单提交) → EXECUTE(自动模式工具循环 AgentTools) → message_end
 * </pre>
 *
 * 状态枚举：SCRIPT_OPTIMIZE(剧本优化) → STORYBOARD_PLAN(分镜方案) → STORYBOARD_JSON(分镜 JSON)
 * → WAITING_FOR_HUMAN(人工确认, 落 agent_checkpoints) → EXECUTE(writeScenes) → DONE。
 * REVIEW 位预留（后续「循环直到完成目标」增强用，当前线性链）。
 *
 * <p>aisplit 链路：剧本优化 LLM（结构化 {type:1|0, message, script}，type=0 直接回答结束；
 * type=1 暂存剧本继续）→ 分镜方案 LLM（结构化 {type, message}）→ 分镜 JSON
 * （复用 {@link ScriptGenerationService#generateScenes}）→ HITL「满意/不满意」→ agree → writeScenes。
 *
 * <p>SSE 协议与前端约定不变（agent.ts SseEvent 7 种事件）。
 */
@Service
@RequiredArgsConstructor
public class AgentOrchestratorImpl implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorImpl.class);

    /** checkpoint 过期时间（对齐原 FORM_SNAPSHOT_TTL_MS：30 分钟） */
    private static final Duration CHECKPOINT_TTL = Duration.ofMinutes(30);
    /** 编排单轮最大步数（防死循环） */
    private static final int MAX_STEPS = 10;

    private final IntentRecognitionService intentRecognitionService;
    private final ScriptGenerationService scriptGenerationService;
    private final com.storyboard.service.ai.ImageRefinePromptService imageRefinePromptService;
    private final com.storyboard.service.ai.VideoPlanService videoPlanService;
    private final AgentTools agentTools;
    private final AgentCheckpointMapper checkpointMapper;
    private final AgentMessageMapper messageMapper;
    private final AiConfigProperties config;
    private final ChatClient.Builder chatClientBuilder;
    private final com.storyboard.service.agent.AgentAnswerService answerService;

    /** 剧本优化 / 分镜方案 LLM（懒加载，复用网关默认视觉模型，超时 120s） */
    private volatile ChatClient planClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 编排状态（含 REVIEW 位预留） */
    private enum Step { SCRIPT_OPTIMIZE, STORYBOARD_PLAN, STORYBOARD_JSON, WAITING_FOR_HUMAN, EXECUTE, REVIEW, DONE }

    /** 本轮最后一条 message 事件内容（供调用方落库 assistant 消息）；run 单线程顺序执行，无并发问题 */
    private String lastMessage = "";

    /** 剧本优化结构化输出 */
    public record ScriptOptimizeResult(int type, String message, String script) {}
    /** 分镜方案结构化输出 */
    public record StoryboardPlanResult(int type, String message) {}
    /** 无图视频方案结构化输出 */
    public record VideoPlanResult(String message, int duration) {}

    @Override
    public String run(AgentConversation conversation, String content, String picUrl, SseEmitter emitter) {
        try {
            lastMessage = "";
            // 1. 意图识别（历史拼最近 8 条）
            List<AgentMessage> recent = messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, conversation.getId())
                    .orderByDesc(AgentMessage::getCreatedAt)
                    .last("LIMIT " + IntentRecognitionService.HISTORY_LIMIT)).reversed();
            String intent = intentRecognitionService.recognize(content, recent);
            log.info("AgentOrchestrator: conversationId={} intent={}", conversation.getId(), intent);

            int steps = 0;
            switch (intent) {
                case "intent-aisplit" -> runAisplit(conversation, content, emitter, steps);
                case "intent-pic" -> runPic(conversation, content, picUrl, emitter, steps);
                case "intent-video" -> runVideo(conversation, content, picUrl, emitter, steps);
                default -> runAnswer(conversation, content, emitter, steps);
            }
            log.info("AgentOrchestrator: conversationId={} 编排完成", conversation.getId());
            return lastMessage;
        } catch (Exception e) {
            log.error("AgentOrchestrator 编排失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "服务异常，请稍后重试"));
            return "";
        } finally {
            try { emitter.complete(); } catch (Exception ignore) { }
        }
    }

    // ===== 意图路由 =====

    /** aisplit 分镜链：剧本优化 gate → 分镜方案 → 分镜 JSON → HITL → 写库 */
    private void runAisplit(AgentConversation conversation, String content, SseEmitter emitter, int steps) {
        // 1. 剧本优化（手动澄清循环：type=0 回答结束，type=1 继续）
        sendEvent(emitter, "workflow", Map.of("title", "正在优化剧本…", "status", "node_started"));
        ScriptOptimizeResult opt = callScriptOptimize(content);
        if (opt == null || opt.type() == 0) {
            sendEvent(emitter, "message", Map.of("content", opt != null ? opt.message() : "已理解你的需求，请继续补充。"));
            return;
        }
        sendEvent(emitter, "message", Map.of("content", opt.message()));
        String script = opt.script() != null && !opt.script().isBlank() ? opt.script() : content;

        // 2. 分镜方案设计（结构化 message）
        sendEvent(emitter, "workflow", Map.of("title", "正在设计分镜方案…", "status", "node_started"));
        StoryboardPlanResult plan = callStoryboardPlan(script);
        if (plan == null || plan.type() == 0) {
            sendEvent(emitter, "message", Map.of("content", plan != null ? plan.message() : "分镜方案需要进一步明确，请补充描述。"));
            return;
        }

        // 3. 分镜 JSON（复用 ScriptGenerationService：LLM 生成 8 字段分镜列表）
        sendEvent(emitter, "workflow", Map.of("title", "正在生成分镜…", "status", "node_started"));
        List<Map<String, Object>> scenes = scriptGenerationService.generateScenes(
            conversation.getProjectId(), script, "movie", null, "16:9", null);
        if (scenes == null || scenes.isEmpty()) {
            sendEvent(emitter, "message", Map.of("content", "⚠ 未能生成分镜内容，请重新描述需求。"));
            return;
        }

        // 4. HITL：方案 + 确认卡片 → checkpoint 落库 → human_input 事件结束本轮
        String planText = "📋 分镜方案（共 " + scenes.size() + " 个镜头）：\n" + summarizeScenes(scenes);
        sendEvent(emitter, "message", Map.of("content", planText));
        String formToken = createCheckpoint(conversation, "agree", scenes, Step.EXECUTE.name());
        sendEvent(emitter, "human_input", Map.of(
            "formToken", formToken, "taskId", "", "formContent", planText,
            "actions", List.of(Map.of("id", "agree", "title", "满意"), Map.of("id", "disagree", "title", "不满意")),
            "expirationTime", OffsetDateTime.now().plus(CHECKPOINT_TTL).toString()));
        return;
    }

    /** pic 图片链（决策 4）：有参考图 → 视觉模型改图方案 + HITL；无图 → LLM 提示词直接文生图（自动完成） */
    private void runPic(AgentConversation conversation, String content, String picUrl, SseEmitter emitter, int steps) {
        String source = picUrl;
        try {
            if (source != null && !source.isBlank()) {
                // 1. 视觉模型看图 + 诉求 → 改图提示词（图改图方案）
                sendEvent(emitter, "workflow", Map.of("title", "正在理解图片与需求…", "status", "node_started"));
                String refinedPrompt = imageRefinePromptService.buildRefinedPrompt(source, content);
                String planText = "🖼 已结合你的参考图与需求，生成了改图方案：\n" + refinedPrompt
                    + "\n\n点击「生成图片」开始生成，或「继续完善」调整需求。";
                sendEvent(emitter, "message", Map.of("content", planText));
                // 2. HITL：方案确认 → checkpoint（action=generate_image，plan 存 prompt+source）
                String formToken = createCheckpoint(conversation, "generate_image",
                    java.util.List.of(Map.of("prompt", refinedPrompt, "source", source)), Step.EXECUTE.name());
                sendEvent(emitter, "human_input", Map.of(
                    "formToken", formToken, "taskId", "", "formContent", planText,
                    "actions", List.of(
                        Map.of("id", "generate_image", "title", "生成图片"),
                        Map.of("id", "refine", "title", "继续完善")),
                    "expirationTime", OffsetDateTime.now().plus(CHECKPOINT_TTL).toString()));
            } else {
                // 无参考图：LLM 生成图片提示词 → 直接文生图（自动完成，无 HITL）
                sendEvent(emitter, "workflow", Map.of("title", "正在生成图片…", "status", "node_started"));
                String prompt = callImagePrompt(content);
                String conversationId = conversation.getId();
                Map<String, Object> result = agentTools.refineImage(conversationId, prompt, null);
                if (Boolean.TRUE.equals(result.get("ok"))) {
                    String url = String.valueOf(result.get("imageUrl"));
                    sendEvent(emitter, "message", Map.of("content", "![生成图片](" + url + ")"));
                    sendEvent(emitter, "confirm_result", Map.of(
                        "kind", "image", "url", url, "assetId", result.getOrDefault("assetId", ""),
                        "sceneCount", 0,
                        "actions", List.of(
                            Map.of("id", "refine", "title", "继续完善"),
                            Map.of("id", "done", "title", "满意完成"))));
                    sendEvent(emitter, "message_end", Map.of(
                        "messageId", "", "sceneCount", -1L, "content", "![生成图片](" + url + ")"));
                } else {
                    sendEvent(emitter, "error", Map.of("code", "50202",
                        "message", String.valueOf(result.getOrDefault("message", "图片生成失败，请稍后重试"))));
                }
            }
        } catch (Exception e) {
            log.error("AgentOrchestrator.runPic 失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "图片方案生成失败，请稍后重试"));
        }
    }

    /** video 视频链（决策 4）：有参考图 → 视觉模型方案；无图 → LLM 方案；均推 video_plan 卡片 → generateVideoFromPlan */
    private void runVideo(AgentConversation conversation, String content, String picUrl, SseEmitter emitter, int steps) {
        String source = picUrl;
        try {
            sendEvent(emitter, "workflow", Map.of("title", "正在设计视频方案…", "status", "node_started"));
            String message;
            int duration;
            if (source != null && !source.isBlank()) {
                // 视觉模型看图 + 诉求 → 视频方案（首帧语义）
                com.storyboard.service.ai.VideoPlanService.VideoPlan plan = videoPlanService.buildVideoPlan(source, content);
                message = plan.message();
                duration = plan.duration();
            } else {
                // 无图：LLM 生成视频方案（prompt + 时长）
                VideoPlanResult plan = callVideoPlan(content);
                message = plan.message();
                duration = plan.duration();
            }
            // 方案快照落 checkpoint（action=generate_video，plan 存 message/duration/source）→ video_plan 卡片
            String planToken = createCheckpoint(conversation, "generate_video",
                java.util.List.of(Map.of("message", message, "duration", duration, "source", source == null ? "" : source)),
                Step.EXECUTE.name());
            String planText = "📹 视频方案：\n" + message + "\n（时长 " + duration + " 秒）";
            sendEvent(emitter, "message", Map.of("content", planText));
            sendEvent(emitter, "video_plan", Map.of(
                "planToken", planToken,
                "message", message,
                "duration", duration,
                "picUrl", source == null ? "" : source,
                "actions", List.of(
                    Map.of("id", "generate_video", "title", "开始生成视频"),
                    Map.of("id", "refine", "title", "继续完善"))));
        } catch (Exception e) {
            log.error("AgentOrchestrator.runVideo 失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "视频方案生成失败，请稍后重试"));
        }
    }

    /** other 回答链（AgentAnswerService 主回答，非流式 message + message_end） */
    private void runAnswer(AgentConversation conversation, String content, SseEmitter emitter, int steps) {
        // 经编排 sendEvent 发射（捕获 lastMessage 供调用方落库），answerService 只负责生成文本
        String answer = answerService.generate(conversation, content);
        sendEvent(emitter, "message", Map.of("content", answer));
        sendEvent(emitter, "message_end", Map.of(
            "messageId", "", "sceneCount", -1L, "content", answer));
    }

    @Override
    public void resume(AgentConversation conversation, String formToken, String action, SseEmitter emitter) {
        try {
            AgentCheckpoint cp = checkpointMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentCheckpoint>()
                    .eq(AgentCheckpoint::getFormToken, formToken)
                    .eq(AgentCheckpoint::getConversationId, conversation.getId())
                    .last("LIMIT 1"));
            if (cp == null) {
                sendEvent(emitter, "error", Map.of("code", "40401", "message", "确认信息不存在或已失效，请重新发起"));
                return;
            }
            if (!"pending".equals(cp.getStatus()) || cp.getExpirationTime() == null
                    || cp.getExpirationTime().isBefore(OffsetDateTime.now())) {
                sendEvent(emitter, "error", Map.of("code", "40001", "message", "确认已过期，请重新发起"));
                return;
            }
            // 一次性消费：status → used（原子条件防并发重放）
            int updated = checkpointMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentCheckpoint>()
                    .eq(AgentCheckpoint::getId, cp.getId())
                    .eq(AgentCheckpoint::getStatus, "pending")
                    .set(AgentCheckpoint::getStatus, "used"));
            if (updated == 0) {
                sendEvent(emitter, "error", Map.of("code", "40001", "message", "确认已被使用，请重新发起"));
                return;
            }

            if ("agree".equals(action)) {
                // 执行写分镜（EXECUTE step：自动模式工具调用）
                sendEvent(emitter, "workflow", Map.of("title", "正在写入分镜…", "status", "node_started"));
                List<AgentSceneItem> items = parsePlanScenes(cp.getPlan());
                int count = agentTools.writeScenes(conversation.getProjectId(), items)
                        .getOrDefault("count", 0) instanceof Number n ? n.intValue() : 0;
                String msg = count > 0
                    ? "✅ 分镜方案已确认，已生成 **" + count + " 个分镜**，请查看左侧分镜列表"
                    : "⚠ 分镜方案已确认，但未解析到分镜内容，请重新描述需求";
                sendEvent(emitter, "message", Map.of("content", msg));
                sendEvent(emitter, "confirm_result", Map.of(
                    "kind", "script", "sceneCount", count, "url", "", "actions", List.of()));
                sendEvent(emitter, "message_end", Map.of(
                    "messageId", "", "sceneCount", count, "content", msg));
            } else if ("generate_image".equals(action)) {
                // 图片方案确认：图改图执行（checkpoint plan 存 prompt+source）
                sendEvent(emitter, "workflow", Map.of("title", "正在生成图片…", "status", "node_started"));
                String prompt = planField(cp.getPlan(), "prompt");
                String source = planField(cp.getPlan(), "source");
                Map<String, Object> result = agentTools.refineImage(conversation.getId(), prompt, source);
                if (Boolean.TRUE.equals(result.get("ok"))) {
                    String url = String.valueOf(result.get("imageUrl"));
                    String content = "![生成图片](" + url + ")";
                    sendEvent(emitter, "message", Map.of("content", content));
                    sendEvent(emitter, "confirm_result", Map.of(
                        "kind", "image", "url", url, "assetId", result.getOrDefault("assetId", ""),
                        "sceneCount", 0,
                        "actions", List.of(
                            Map.of("id", "refine", "title", "继续完善"),
                            Map.of("id", "done", "title", "满意完成"))));
                    sendEvent(emitter, "message_end", Map.of(
                        "messageId", "", "sceneCount", -1L, "content", content));
                } else {
                    sendEvent(emitter, "error", Map.of("code", "50202",
                        "message", String.valueOf(result.getOrDefault("message", "图片生成失败，请稍后重试"))));
                }
            } else {
                sendEvent(emitter, "message", Map.of("content", "好的，请继续完善设计方案。"));
                sendEvent(emitter, "message_end", Map.of(
                    "messageId", "", "sceneCount", -1L, "content", "好的，请继续完善设计方案。"));
            }
        } catch (Exception e) {
            log.error("AgentOrchestrator.resume 失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "服务异常，请稍后重试"));
        } finally {
            try { emitter.complete(); } catch (Exception ignore) { }
        }
    }

    // ===== LLM 调用（结构化输出：纯解析，不发 response_format）=====

    /** 剧本优化：手动澄清循环的单步调用 */
    private ScriptOptimizeResult callScriptOptimize(String content) {
        try {
            String raw = planClient().prompt()
                .system("你是分镜助手，先理解用户的分镜需求并给出优化后的剧本。"
                    + "输出 JSON：{\"type\":1或0,\"message\":\"给用户的回复\",\"script\":\"优化后的完整剧本\"}"
                    + "。type=1 表示已理解可继续；type=0 表示需求不足需追问（此时 message 为追问内容，script 为空）。")
                .user(content)
                .call()
                .content();
            return new BeanOutputConverter<>(ScriptOptimizeResult.class).convert(raw);
        } catch (Exception e) {
            log.warn("剧本优化 LLM 调用失败: {}", e.getMessage());
            return new ScriptOptimizeResult(0, "已理解你的需求，请继续补充。", null);
        }
    }

    /** 分镜方案设计 */
    private StoryboardPlanResult callStoryboardPlan(String script) {
        try {
            String raw = planClient().prompt()
                .system("你是分镜方案设计师。基于剧本给出分镜方案要点。"
                    + "输出 JSON：{\"type\":1或0,\"message\":\"方案说明\"}。type=1 方案已明确；type=0 需用户补充（message 为追问）。")
                .user(script)
                .call()
                .content();
            return new BeanOutputConverter<>(StoryboardPlanResult.class).convert(raw);
        } catch (Exception e) {
            log.warn("分镜方案 LLM 调用失败: {}", e.getMessage());
            return new StoryboardPlanResult(1, "已根据剧本生成方案。");
        }
    }

    /** 无参考图文生图：LLM 生成图片提示词 */
    private String callImagePrompt(String content) {
        try {
            String raw = planClient().prompt()
                .system("你是 AI 绘画提示词工程师。根据用户的需求输出一条高质量图片生成提示词，"
                    + "只输出提示词文本本身，不要任何解释、引号或前后缀。")
                .user(content)
                .call()
                .content();
            return raw != null && !raw.isBlank() ? raw.trim() : content;
        } catch (Exception e) {
            log.warn("图片提示词 LLM 调用失败: {}", e.getMessage());
            return content;
        }
    }

    /** 无参考图视频：LLM 生成视频方案（prompt + 时长） */
    private VideoPlanResult callVideoPlan(String content) {
        try {
            String raw = planClient().prompt()
                .system("你是视频生成方案设计师。根据用户需求设计视频 prompt。"
                    + "输出 JSON：{\"message\":\"视频生成 prompt，中文 50~120 字，描述动作/运镜/光线/氛围\",\"duration\":4~15 整数}。"
                    + "只输出 JSON。")
                .user(content)
                .call()
                .content();
            return new BeanOutputConverter<>(VideoPlanResult.class).convert(raw);
        } catch (Exception e) {
            log.warn("视频方案 LLM 调用失败: {}", e.getMessage());
            return new VideoPlanResult(content, 8);
        }
    }

    /** checkpoint plan JSON 取字段（宽松解析，缺失返回空串） */
    private String planField(String planJson, String field) {
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

    private ChatClient planClient() {
        if (planClient == null) {
            synchronized (this) {
                if (planClient == null) {
                    planClient = chatClientBuilder
                        .defaultOptions(OpenAiChatOptions.builder()
                            .model(config.getDefaultVisionModel())
                            .timeout(Duration.ofSeconds(120)))
                        .build();
                }
            }
        }
        return planClient;
    }

    // ===== checkpoint 落库 =====

    /** 创建 HITL checkpoint（pending，30min 过期），返回 form_token */
    private String createCheckpoint(AgentConversation conversation, String action,
                                    List<Map<String, Object>> scenes, String step) {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setConversationId(conversation.getId());
        cp.setAction(action);
        cp.setFormToken(UUID.randomUUID().toString());
        cp.setStep(step);
        cp.setStatus("pending");
        cp.setExpirationTime(OffsetDateTime.now().plus(CHECKPOINT_TTL));
        try {
            cp.setPlan(objectMapper.writeValueAsString(Map.of("items", scenes)));
        } catch (Exception e) {
            log.warn("checkpoint plan 序列化失败: {}", e.getMessage());
        }
        checkpointMapper.insert(cp);
        log.info("AgentOrchestrator: checkpoint 已落库 conversationId={} formToken={} action={}",
            conversation.getId(), cp.getFormToken(), action);
        return cp.getFormToken();
    }

    /** checkpoint plan JSON → AgentSceneItem 列表（宽松解析） */
    private List<AgentSceneItem> parsePlanScenes(String planJson) {
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

    /** 分镜概要（HITL 确认卡片文本） */
    private String summarizeScenes(List<Map<String, Object>> scenes) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(scenes.size(), 5);
        for (int i = 0; i < max; i++) {
            Map<String, Object> s = scenes.get(i);
            String content = s.get("scriptContent") instanceof String c ? c : "";
            if (content.length() > 60) content = content.substring(0, 60) + "…";
            sb.append(i + 1).append(". ").append(content).append("\n");
        }
        if (scenes.size() > max) sb.append("…等 ").append(scenes.size()).append(" 个镜头");
        return sb.toString();
    }

    /** SseEmitter 事件发送（前端断开忽略） */
    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        if ("message".equals(eventName) && data.get("content") != null) {
            lastMessage = String.valueOf(data.get("content"));
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            log.debug("SseEmitter 发送失败（前端可能已断开）: event={}", eventName);
        }
    }
}