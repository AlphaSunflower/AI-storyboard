package com.moon.moonagent.ai.agent.specialist.impl;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.AgentTools;
import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.FileStorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 通用闲聊专家 —— ChatClient 自主推理 + 工具调用。
 *
 * <p>当意图为 intent-other 时使用。无固定脚本，ChatClient 自由对话。
 * 工具面为空（闲聊不需要生成工具），后续可通过 SKILL.md 的 tools 字段动态注入。
 *
 * <p>ponytail: 简单实现——直接 ChatClient.call()，不做 tool loop（闲聊不需要）。
 * 升级路径：加 Advisor（记忆/RAG）+ tool loop。
 *
 * <p>2026-09-06：改用 {@code chatResponse()} 读取 reasoning_content（兼容 reasoning_content/thoughts/thinking），
 * 正文仍发 message 增量；模型不返回 reasoning 时记录 trace 摘要。
 */
@Component
@RequiredArgsConstructor
public class OtherSpecialist implements SpecialistAgent {

    private static final Logger log = LoggerFactory.getLogger(OtherSpecialist.class);

    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;
    private final AgentMessageMapper messageMapper;
    private final FileStorageService fileStorageService;

    private volatile ChatClient chatClient;

    @Override
    public String intentType() {
        return "intent-other";
    }

    @Override
    public SpecialistResult execute(SpecialistContext context) {
        try {
            SseEmitter emitter = context.emitter();
            // 拼历史消息
            List<AgentMessage> recent = messageMapper.selectList(
                    new LambdaQueryWrapper<AgentMessage>()
                            .eq(AgentMessage::getConversationId, context.conversation().getId())
                            .orderByDesc(AgentMessage::getCreatedAt)
                            .last("LIMIT 20")).reversed();

            StringBuilder history = new StringBuilder();
            for (AgentMessage msg : recent) {
                history.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
            }

            OffsetDateTime start = OffsetDateTime.now();
            ChatResponse response = getClient().prompt()
                    .system("你是 Moon 智能体，一个专业的分镜创作助手。用中文回答，简洁友好。")
                    .messages(buildUserMessage(history + "\n用户: " + context.userMessage(), context.picUrls()))
                    .call()
                    .chatResponse();
            String answer = response != null && response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
            Long durationMs = Duration.between(start, OffsetDateTime.now()).toMillis();

            // 发送消息增量（模拟流式：一次性发送）
            if (emitter != null && answer != null && !answer.isBlank()) {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name("message").data(com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport.sseJson(Map.of("content", answer))));
            }

            // reasoning
            String reasonDelta = extractReasoning(response != null && response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getMetadata() : null);
            AgentReasoningSnapshot reasoning;
            if (reasonDelta != null && !reasonDelta.isBlank()) {
                if (emitter != null) {
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .name("reasoning_delta").data(com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport.sseJson(Map.of("delta", reasonDelta))));
                }
                reasoning = new AgentReasoningSnapshot(summarize(reasonDelta), reasonDelta, durationMs, gatewayModelService.getDefaultTextModel());
            } else {
                // 无 reasoning：记录 trace 摘要
                String trace = "模型未返回可见推理过程，已生成回答（" + (answer == null ? "0" : answer.length()) + " 字符）。";
                reasoning = new AgentReasoningSnapshot(trace, trace, durationMs, "trace-summary");
            }
            // 把 reasoning 回写到 context trace / request metadata（供 turn 落库）；SpecialistResult 不直接带 metadata，
            // 交由 AgentTraceAccumulator 记录（若 trace 非空）。
            if (context.trace() != null) context.trace().reasoning(reasoning);

            return SpecialistResult.done(answer != null ? answer : "");
        } catch (Exception e) {
            log.error("OtherSpecialist 执行失败: {}", e.getMessage(), e);
            return SpecialistResult.done("抱歉，处理出了点问题，请稍后重试。");
        }
    }

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

    private String summarize(String text) {
        if (text == null || text.isBlank()) return "";
        String t = text.trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    /** 构造用户消息：picUrls 非空时循环读本地图转 data URI 作为多模态图片加入（多张）；全失败降级纯文本 */
    private UserMessage buildUserMessage(String text, java.util.List<String> picUrls) {
        if (picUrls != null && !picUrls.isEmpty()) {
            try {
                var mb = UserMessage.builder().text(text);
                java.util.List<Media> medias = new java.util.ArrayList<>();
                for (String picUrl : picUrls) {
                    if (picUrl == null || picUrl.isBlank()) continue;
                    String dataUri = fileStorageService.toImageDataUri(picUrl);
                    int sep = dataUri.indexOf(';');
                    String mime = dataUri.startsWith("data:") && sep > 5 ? dataUri.substring(5, sep) : "image/png";
                    medias.add(Media.builder().mimeType(MimeType.valueOf(mime)).data(dataUri).build());
                }
                if (!medias.isEmpty()) return mb.media(medias).build();
            } catch (Exception e) {
                log.warn("参考图加入主回答失败，降级纯文本: {}", e.getMessage());
            }
        }
        return UserMessage.builder().text(text).build();
    }

    private ChatClient getClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    String model = gatewayModelService.getDefaultTextModel();
                    chatClient = chatClientBuilder
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(model)
                                    .timeout(Duration.ofSeconds(60)))
                            .build();
                }
            }
        }
        return chatClient;
    }
}
