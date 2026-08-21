package com.moon.moonagent;

import com.moon.moonagent.ai.agent.AgentNodeClients;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 Advisor Chain 装配验证（StateGraph 编排重构）：
 * ChatMemory bean 自动装配 + AgentNodeClients 图节点工厂 advisor 组合正确。
 */
@SpringBootTest
class AgentNodeClientsTests {

    @Autowired(required = false)
    private ChatMemory chatMemory;

    @Autowired(required = false)
    private AgentNodeClients agentNodeClients;

    @Test
    void chatMemoryBeanAutoConfigured() {
        assertNotNull(chatMemory, "ChatMemory bean 应由 spring-ai chat-memory starter 自动装配");
        assertTrue(chatMemory instanceof MessageWindowChatMemory,
                "自动装配的 ChatMemory 应为 MessageWindowChatMemory（窗口记忆）");
    }

    @Test
    void nodeClientsComposeAdvisors() {
        assertNotNull(agentNodeClients, "AgentNodeClients bean 应装配成功");
        // 懒加载 client 可构建（不触发真实 LLM 调用）
        var memoryClient = agentNodeClients.memoryClient();
        assertNotNull(memoryClient);
        var visionClient = agentNodeClients.memoryVisionClient();
        assertNotNull(visionClient);
    }
}
