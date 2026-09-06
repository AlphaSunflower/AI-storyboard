package com.moon.moonagent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@TestPropertySource(properties = "storyboard.internal-secret=test-internal-secret")
@Transactional
class HitlCardPersistenceTest {

    @Autowired
    private AgentOrchestratorSupport support;

    @Autowired
    private AgentConversationMapper conversationMapper;

    @Autowired
    private AgentCheckpointMapper checkpointMapper;

    @Autowired
    private AgentMessageMapper messageMapper;

    @Test
    void submittedCardStaysInMessagesWithSelectedActionAndReasoning() {
        AgentConversation conversation = new AgentConversation();
        conversation.setUserId("hitl-card-user");
        conversation.setProjectId("96b5f8605775d73265694a8d818a6a7e");
        conversation.setTitle("hitl-card-test");
        conversation.setStatus("active");
        conversationMapper.insert(conversation);

        OrchestrationRequest request = new OrchestrationRequest(conversation, "测试需求", List.of(), new SseEmitter(0L));
        var plan = new AgentOrchestratorSupport.StagePlan(
                "请选择风格",
                "test-style",
                List.of(Map.of("content", "测试需求")),
                "human_input",
                List.of(Map.of("id", "style1", "title", "赛博朋克")));

        support.runHITLStage(request, null, plan);

        AgentCheckpoint checkpoint = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentCheckpoint>()
                        .eq(AgentCheckpoint::getConversationId, conversation.getId())
                        .eq(AgentCheckpoint::getStatus, "pending")
                        .last("LIMIT 1"));
        support.markHitlCardSubmitted(conversation.getId(), checkpoint.getFormToken(),
                "style1", "", Map.of(), List.of());

        AgentMessage message = messageMapper.selectById(request.getHitlMessageId());
        assertFalse(Boolean.TRUE.equals(message.getMetadata().get("pending")));
        @SuppressWarnings("unchecked")
        Map<String, Object> hitl = (Map<String, Object>) message.getMetadata().get("hitl");
        assertEquals("style1", hitl.get("selectedActionId"));
        assertEquals("赛博朋克", hitl.get("selectedActionTitle"));
    }
}
