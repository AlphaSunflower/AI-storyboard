package com.moon.moonagent.ai.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.ai.agent.AgentAnswerResult;
import com.moon.moonagent.ai.agent.AgentAnswerService;
import com.moon.moonagent.ai.agent.AgentInterruptException;
import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.IntentRecognitionService;
import com.moon.moonagent.ai.AiConfigProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.service.FileStorageService;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

/**
 * Agent 主回答实现：ChatClient 拼历史，支持流式正文 + reasoning delta。
 *
 * <p>D3 定案（2026-08-11 Phase 0 spike）：非流式。前端打字机按 message 事件
 * 逐段拼接，整段 message 也兼容；流式列为后续增强（Phase 0 汇报 D3 结论）。
 * <p>2026-09-06：新增 {@link #streamAnswerWithResult}，使用 {@code chatResponse()} 读取
 * reasoning_content（兼容 reasoning_content/thoughts/thinking），正文仍按 message 增量发送。
 */
@Service
@RequiredArgsConstructor
public class AgentAnswerServiceImpl implements AgentAnswerService {

    private static final Logger log = LoggerFactory.getLogger(AgentAnswerServiceImpl.class);

    private final AgentMessageMapper messageMapper;
    private final AiConfigProperties config;
    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;
    private final com.moon.moonagent.config.PromptConfig promptConfig;
    private final FileStorageService fileStorageService;

    /** 主回答 ChatClient（懒加载，默认视觉模型，超时 60s） */
    private volatile ChatClient chatClient;

    @Override
    public String generate(AgentConversation conversation, String content, String picUrl) {
        try {
            String answer = chatClient().prompt().options(OpenAiChatOptions.builder().model(gatewayModelService.getDefaultTextModel()))
                .system(promptConfig.get("services/agent-answer"))
                .messages(buildUserMessage("对话历史：\n" + buildHistory(conversation.getId()) + "\n\n用户最新消息：\n" + content, picUrl))
                .call()
                .content();
            return answer != null && !answer.isBlank() ? answer : "我在的，请描述你的创作需求～";
        } catch (Exception e) {
            log.error("AgentAnswerService 回答失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            return "服务开小差了，请稍后再试。";
        }
    }

    @Override
    public String streamAnswer(AgentConversation conversation, String content, String picUrl, SseEmitter emitter) {
        return streamAnswerWithResult(conversation, content, picUrl, emitter).content();
    }

    @Override
    public AgentAnswerResult streamAnswerWithResult(AgentConversation conversation, String content, String picUrl, SseEmitter emitter) {
        return streamAnswerWithResult(conversation, content, picUrl, emitter, null);
    }

    @Override
    public AgentAnswerResult streamAnswerWithResult(AgentConversation conversation, String content, String picUrl,
                                                    SseEmitter emitter,
                                                    com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator trace) {
        StringBuilder sb = new StringBuilder();
        String reasonText = "";
        OffsetDateTime start = OffsetDateTime.now();
        try {
            // Spring AI 2.0 的流式 chatResponse 在 OpenAI SDK chunk 转换时会丢掉
            // reasoning_content，因此这里取非流式响应，保留模型真实思考内容后一次转发。
            if (trace != null) trace.beginModelCall();
            ChatResponse response = chatClient().prompt()
                    .options(OpenAiChatOptions.builder().model(gatewayModelService.getDefaultTextModel()))
                    .system(promptConfig.get("services/agent-answer"))
                    .messages(buildUserMessage("对话历史：\n" + buildHistory(conversation.getId()) + "\n\n用户最新消息：\n" + content, picUrl))
                    .call()
                    .chatResponse();
            if (trace != null) trace.recordUsage(response);
            AssistantMessage output = response != null && response.getResult() != null
                    ? response.getResult().getOutput() : null;
            String chunk = output != null ? output.getText() : null;
            if (chunk != null && !chunk.isBlank()) {
                sb.append(chunk);
                try {
                    emitter.send(SseEmitter.event().name("message").data(
                            com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport.sseJson(
                                    java.util.Map.of("content", chunk))));
                } catch (Exception e) {
                    log.debug("SseEmitter 发送失败（前端可能已断开）: conversationId={}", conversation.getId());
                }
            }
            String reasonDelta = extractReasoning(output == null ? null : output.getMetadata());
            if (reasonDelta != null && !reasonDelta.isBlank()) {
                reasonText = reasonDelta;
                try {
                    emitter.send(SseEmitter.event().name("reasoning_delta").data(
                            com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport.sseJson(java.util.Map.of(
                                    "details", reasonDelta,
                                    "summary", summarize(reasonDelta),
                                    "source", gatewayModelService.getDefaultTextModel()))));
                } catch (Exception e) {
                    log.debug("SseEmitter reasoning 发送失败（前端可能已断开）: conversationId={}", conversation.getId());
                }
            }
        } catch (AgentInterruptException e) {
            throw e; // 用户打断：跳过降级文案补发，交给编排壳层收尾
        } catch (Exception e) {
            log.error("AgentAnswerService 流式回答失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
        }
        String full = sb.toString();
        if (full.isBlank()) {
            // LLM 失败或流中断：降级文案补发一条 message（前端打字机仍需内容收尾）
            full = "服务开小差了，请稍后再试。";
            try {
                emitter.send(SseEmitter.event().name("message").data(com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport.sseJson(java.util.Map.of("content", full))));
            } catch (Exception ignore) { }
        }
        Long durationMs = Duration.between(start, OffsetDateTime.now()).toMillis();
        AgentReasoningSnapshot reasoning = !reasonText.isBlank()
                ? new AgentReasoningSnapshot(summarize(reasonText), reasonText,
                        durationMs, gatewayModelService.getDefaultTextModel())
                : new AgentReasoningSnapshot(
                        "模型未返回可见推理过程，已生成回答（" + full.length() + " 字符）。",
                        "模型未返回可见推理过程，已生成回答（" + full.length() + " 字符）。",
                        durationMs, "trace-summary");
        return AgentAnswerResult.of(full, reasoning, reasoning.source());
    }

    @Override
    public String answer(AgentConversation conversation, String content, String picUrl, SseEmitter emitter) {
        String answer = generate(conversation, content, picUrl);
        try {
            emitter.send(SseEmitter.event().name("message").data(com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport.sseJson(java.util.Map.of("content", answer))));
        } catch (Exception e) {
            log.debug("SseEmitter 发送失败（前端可能已断开）: conversationId={}", conversation.getId());
        }
        return answer;
    }

    /** 从 AssistantMessage metadata 提取 reasoning（兼容 reasoning_content / reasoningContent / thoughts / thinking）。 */
    private String extractReasoning(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        for (String key : new String[]{"reasoning_content", "reasoningContent", "thoughts", "thinking"}) {
            Object v = metadata.get(key);
            if (v == null) continue;
            String s = v instanceof String str ? str : String.valueOf(v);
            if (s != null && !s.isBlank()) return s;
        }
        return null;
    }

    /** 摘要：首 200 字符 + 省略号。 */
    private String summarize(String text) {
        if (text == null || text.isBlank()) return "";
        String t = text.trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    /** 构造用户消息：picUrl 非空时读本地图转 data URI 作为多模态图片加入；读图失败降级纯文本 */
    private UserMessage buildUserMessage(String text, String picUrl) {
        if (picUrl != null && !picUrl.isBlank()) {
            try {
                String dataUri = fileStorageService.toImageDataUri(picUrl);
                int sep = dataUri.indexOf(';');
                String mime = dataUri.startsWith("data:") && sep > 5 ? dataUri.substring(5, sep) : "image/png";
                Media media = Media.builder().mimeType(MimeType.valueOf(mime)).data(dataUri).build();
                return UserMessage.builder().text(text).media(media).build();
            } catch (Exception e) {
                log.warn("参考图加入主回答失败，降级纯文本: {}", e.getMessage());
            }
        }
        return UserMessage.builder().text(text).build();
    }

    /** 会话历史（最近 8 条，时间升序） */
    private String buildHistory(String conversationId) {
        List<AgentMessage> history = messageMapper.selectList(
            new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .orderByDesc(AgentMessage::getCreatedAt)
                .last("LIMIT " + IntentRecognitionService.HISTORY_LIMIT));
        StringBuilder sb = new StringBuilder();
        for (AgentMessage m : history.reversed()) {
            String role = "user".equals(m.getRole()) ? "用户" : "助手";
            String content = m.getContent();
            if (content != null && content.length() > 500) content = content.substring(0, 500) + "…";
            sb.append(role).append("：").append(content).append("\n");
        }
        return sb.toString();
    }

    private ChatClient chatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = chatClientBuilder
                            // P1: 主回答挂审计日志 advisor（验证 Advisor Chain 管线；历史仍手拼，不挂记忆 advisor 防双份）
                            .defaultAdvisors(new SimpleLoggerAdvisor())
                            .defaultOptions(OpenAiChatOptions.builder()
                                    // 对话交流统一网关默认文本模型（动态获取）
                                    .timeout(Duration.ofSeconds(300)))
                            .build();
                }
            }
        }
        return chatClient;
    }
}
