package com.moon.moonagent.ai.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.ai.agent.AgentAnswerService;
import com.moon.moonagent.ai.agent.IntentRecognitionService;
import com.moon.moonagent.ai.AiConfigProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import com.moon.moonagent.ai.GatewayModelService;

/**
 * Agent 主回答实现：ChatClient 拼历史，非流式。
 *
 * <p>D3 定案（2026-08-11 Phase 0 spike）：非流式。前端打字机按 message 事件
 * 逐段拼接，整段 message 也兼容；流式列为后续增强（Phase 0 汇报 D3 结论）。
 */
@Service
@RequiredArgsConstructor
public class AgentAnswerServiceImpl implements AgentAnswerService {

    private static final Logger log = LoggerFactory.getLogger(AgentAnswerServiceImpl.class);

    private final AgentMessageMapper messageMapper;
    private final AiConfigProperties config;
    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;

    /** 主回答 ChatClient（懒加载，默认视觉模型，超时 60s） */
    private volatile ChatClient chatClient;

    @Override
    public String generate(AgentConversation conversation, String content) {
        try {
            String answer = chatClient().prompt()
                .system("你是 Moon 智能体，AI Storyboard 平台的创作助手。"
                    + "你可以帮用户写分镜、生成图片、生成视频。回答简洁自然，使用中文。"
                    + "如果用户请求的是分镜/图片/视频创作，引导对方用明确指令描述需求（如\"帮我做个清朝灭亡的分镜\"）。")
                .user("对话历史：\n" + buildHistory(conversation.getId()) + "\n\n用户最新消息：\n" + content)
                .call()
                .content();
            return answer != null && !answer.isBlank() ? answer : "我在的，请描述你的创作需求～";
        } catch (Exception e) {
            log.error("AgentAnswerService 回答失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
            return "服务开小差了，请稍后再试。";
        }
    }

    @Override
    public String streamAnswer(AgentConversation conversation, String content, SseEmitter emitter) {
        StringBuilder sb = new StringBuilder();
        try {
            chatClient().prompt()
                .system("你是 Moon 智能体，AI Storyboard 平台的创作助手。"
                    + "你可以帮用户写分镜、生成图片、生成视频。回答简洁自然，使用中文。"
                    + "如果用户请求的是分镜/图片/视频创作，引导对方用明确指令描述需求（如\"帮我做个清朝灭亡的分镜\"）。")
                .user("对话历史：\n" + buildHistory(conversation.getId()) + "\n\n用户最新消息：\n" + content)
                .stream()
                .content()
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.isBlank()) return;
                    sb.append(chunk);
                    try {
                        emitter.send(SseEmitter.event().name("message").data(java.util.Map.of("content", chunk)));
                    } catch (Exception e) {
                        log.debug("SseEmitter 发送失败（前端可能已断开）: conversationId={}", conversation.getId());
                    }
                })
                .blockLast();
        } catch (Exception e) {
            log.error("AgentAnswerService 流式回答失败: conversationId={}, error={}", conversation.getId(), e.getMessage(), e);
        }
        String full = sb.toString();
        if (full.isBlank()) {
            // LLM 失败或流中断：降级文案补发一条 message（前端打字机仍需内容收尾）
            full = "服务开小差了，请稍后再试。";
            try {
                emitter.send(SseEmitter.event().name("message").data(java.util.Map.of("content", full)));
            } catch (Exception ignore) { }
        }
        return full;
    }

    @Override
    public String answer(AgentConversation conversation, String content, SseEmitter emitter) {
        String answer = generate(conversation, content);
        try {
            emitter.send(SseEmitter.event().name("message").data(java.util.Map.of("content", answer)));
        } catch (Exception e) {
            log.debug("SseEmitter 发送失败（前端可能已断开）: conversationId={}", conversation.getId());
        }
        return answer;
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
                            .defaultOptions(OpenAiChatOptions.builder()
                                    // 对话交流统一网关默认文本模型（动态获取）
                                    .model(gatewayModelService.getDefaultTextModel())
                                    .timeout(Duration.ofSeconds(120)))
                            .build();
                }
            }
        }
        return chatClient;
    }
}
