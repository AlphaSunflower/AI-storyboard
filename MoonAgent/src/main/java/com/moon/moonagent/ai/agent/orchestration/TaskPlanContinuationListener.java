package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskPlanContinuationListener {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanContinuationListener.class);

    private final DynamicTaskEngine dynamicTaskEngine;
    private final AgentConversationMapper conversationMapper;

    @EventListener
    public void onTaskTerminal(TaskAssetTerminalEvent event) {
        if (event == null || event.planId() == null || event.planId().isBlank()) return;
        AgentConversation conversation = conversationMapper.selectById(event.conversationId());
        if (conversation == null) {
            log.warn("task continuation conversation missing: {}", event.conversationId());
            return;
        }
        dynamicTaskEngine.continueAfterAsync(
                conversation, event.planId(), event.nodeId(), event.success(), event.summary());
    }
}
