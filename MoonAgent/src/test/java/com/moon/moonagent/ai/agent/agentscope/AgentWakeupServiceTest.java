package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.task.AgentTaskEventHub;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import io.agentscope.harness.agent.bus.MessageBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Mono;

class AgentWakeupServiceTest {

    @Test
    void consumesPendingWakeupAndPersistsOneAssistantMessage() {
        Fixture fixture = fixture();
        AgentAsyncTask task = longTask();
        when(fixture.taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));
        when(fixture.messageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(fixture.conversationLock.tryAcquire("conversation-1")).thenReturn(true);
        when(fixture.mediaRuntime.runWakeup(fixture.conversation)).thenReturn(turn());
        AgentMessage message = new AgentMessage();
        message.setId("message-wakeup-1");
        when(fixture.chatService.persistWakeupResult(any(), any())).thenReturn(message);

        fixture.service.runWakeup("user-1", "conversation-1").block();

        verify(fixture.mediaRuntime).runWakeup(fixture.conversation);
        verify(fixture.chatService).persistWakeupResult(any(), any());
        verify(fixture.taskEvents).publishWakeup(
                org.mockito.ArgumentMatchers.eq("user-1"), any());
        verify(fixture.conversationLock).release("conversation-1");
        assertThat(fixture.updates.stream()
                .flatMap(update -> update.getParamNameValuePairs().values().stream())
                .map(String::valueOf)
                .toList()).contains("consumed", "message-wakeup-1");
    }

    @Test
    void requeuesWakeupInsteadOfDroppingItWhenConversationIsLocked() {
        Fixture fixture = fixture();
        when(fixture.taskMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(longTask()));
        when(fixture.conversationLock.tryAcquire("conversation-1")).thenReturn(false);
        when(fixture.bus.enqueueWakeup("user-1", "conversation-1", "moon-aigc"))
                .thenReturn(Mono.empty());

        fixture.service.runWakeup("user-1", "conversation-1").block();

        verify(fixture.bus).enqueueWakeup("user-1", "conversation-1", "moon-aigc");
        verify(fixture.mediaRuntime, never()).runWakeup(any());
        verify(fixture.conversationLock, never()).release(any());
    }

    private static Fixture fixture() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.setWakeupEnabled(true);
        properties.setWakeupMaxRetries(5);
        AgentScopeMessageBus configuredBus = mock(AgentScopeMessageBus.class);
        MessageBus bus = mock(MessageBus.class);
        when(configuredBus.enabled()).thenReturn(true);
        when(configuredBus.messageBus()).thenReturn(bus);
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        MoonAgentRuntime mediaRuntime = mock(MoonAgentRuntime.class);
        AgentChatService chatService = mock(AgentChatService.class);
        AgentTaskEventHub taskEvents = mock(AgentTaskEventHub.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(taskMapper.update(any(), any())).thenReturn(1);
        AgentWakeupService service = new AgentWakeupService(
                properties, configuredBus, mediaRuntime, chatService, lock,
                taskMapper, conversationMapper, messageMapper, taskEvents);
        return new Fixture(
                service, configuredBus, bus, taskMapper, conversationMapper, messageMapper,
                lock, mediaRuntime, chatService, taskEvents, conversation);
    }

    private static AgentAsyncTask longTask() {
        AgentAsyncTask task = new AgentAsyncTask();
        task.setId("task-1");
        task.setConversationId("conversation-1");
        task.setUserId("user-1");
        task.setKind("long-tool");
        task.setStatus("completed");
        task.setWakeupStatus("enqueued");
        task.setWakeupAttempts(1);
        return task;
    }

    private static AgentTurnResult turn() {
        return new AgentTurnResult(
                "后台任务完成", Map.of("kind", "assistant-reply"),
                List.of(), null, List.of(), false, "");
    }

    private static final class Fixture {
        private final AgentWakeupService service;
        private final AgentScopeMessageBus configuredBus;
        private final MessageBus bus;
        private final AgentAsyncTaskMapper taskMapper;
        private final AgentConversationMapper conversationMapper;
        private final AgentMessageMapper messageMapper;
        private final ConversationLock conversationLock;
        private final MoonAgentRuntime mediaRuntime;
        private final AgentChatService chatService;
        private final AgentTaskEventHub taskEvents;
        private final AgentConversation conversation;
        private final java.util.List<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AgentAsyncTask>>
                updates = new java.util.ArrayList<>();

        private Fixture(
                AgentWakeupService service,
                AgentScopeMessageBus configuredBus,
                MessageBus bus,
                AgentAsyncTaskMapper taskMapper,
                AgentConversationMapper conversationMapper,
                AgentMessageMapper messageMapper,
                ConversationLock conversationLock,
                MoonAgentRuntime mediaRuntime,
                AgentChatService chatService,
                AgentTaskEventHub taskEvents,
                AgentConversation conversation) {
            this.service = service;
            this.configuredBus = configuredBus;
            this.bus = bus;
            this.taskMapper = taskMapper;
            this.conversationMapper = conversationMapper;
            this.messageMapper = messageMapper;
            this.conversationLock = conversationLock;
            this.mediaRuntime = mediaRuntime;
            this.chatService = chatService;
            this.taskEvents = taskEvents;
            this.conversation = conversation;
            try {
                when(taskMapper.update(any(), any())).thenAnswer(invocation -> {
                    updates.add(invocation.getArgument(1));
                    return 1;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
