package com.moon.moonagent.ai.agent;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 图节点专用 ChatClient 工厂（StateGraph 编排重构 P1）：
 *
 * <p>每个图节点按需组合 Advisor 横切能力——对话记忆（{@link MessageChatMemoryAdvisor}）、
 * 审计日志（{@link SimpleLoggerAdvisor}）、工具调用（{@code ChatClient.tools()} 自动
 * ToolCallingAdvisor 循环，同步工具）。存量服务（手拼历史进 prompt）不迁移至此，
 * 避免历史双份；仅 StateGraph 节点使用。
 */
@Component
@RequiredArgsConstructor
public class AgentNodeClients {

    /** 节点 LLM 默认超时（对齐编排 planClient 120s） */
    private static final Duration NODE_TIMEOUT = Duration.ofSeconds(120);

    private final ChatClient.Builder chatClientBuilder;
    private final AgentAiConfigProperties agentConfig;
    private final GatewayModelService gatewayModelService;
    private final ChatMemory chatMemory;

    /**
     * 带对话记忆 + 审计日志的节点 ChatClient（意图识别 / 方案生成 / 计划生成等纯 LLM 节点）。
     * conversationId 通过 {@code .memory(conversationId)} 或 advisor 参数传入。
     */
    public ChatClient memoryClient() {
        return chatClientBuilder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(gatewayModelService.getDefaultTextModel())
                        .timeout(NODE_TIMEOUT))
                .build();
    }

    /**
     * 带对话记忆 + 审计日志的视觉节点 ChatClient（图改图 prompt / 图生视频方案等看图节点）。
     */
    public ChatClient memoryVisionClient() {
        return chatClientBuilder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(gatewayModelService.getDefaultVisionModel())
                        .timeout(NODE_TIMEOUT))
                .build();
    }
}
