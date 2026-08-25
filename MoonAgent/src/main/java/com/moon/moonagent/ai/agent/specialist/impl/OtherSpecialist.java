package com.moon.moonagent.ai.agent.specialist.impl;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.AgentTools;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
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
 */
@Component
@RequiredArgsConstructor
public class OtherSpecialist implements SpecialistAgent {

    private static final Logger log = LoggerFactory.getLogger(OtherSpecialist.class);

    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;
    private final AgentMessageMapper messageMapper;

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

            String answer = getClient().prompt()
                    .system("你是 Moon 智能体，一个专业的分镜创作助手。用中文回答，简洁友好。")
                    .user(history + "\n用户: " + context.userMessage())
                    .call()
                    .content();

            // 发送消息增量（模拟流式：一次性发送）
            if (emitter != null && answer != null && !answer.isBlank()) {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name("message").data(Map.of("content", answer)));
            }

            return SpecialistResult.done(answer != null ? answer : "");
        } catch (Exception e) {
            log.error("OtherSpecialist 执行失败: {}", e.getMessage(), e);
            return SpecialistResult.done("抱歉，处理出了点问题，请稍后重试。");
        }
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
