package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.task.AgentTaskEventHub;
import com.moon.moonagent.ai.agent.task.AgentWakeupMessage;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.WakeupDispatcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled", havingValue = "true")
public class AgentWakeupService implements WakeupDispatcher.WakeupTarget {

    private static final Logger log = LoggerFactory.getLogger(AgentWakeupService.class);

    private final AgentScopeProperties properties;
    private final AgentScopeMessageBus messageBus;
    private final MoonAgentRuntime mediaRuntime;
    private final AgentChatService chatService;
    private final ConversationLock conversationLock;
    private final AgentAsyncTaskMapper taskMapper;
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentTaskEventHub taskEvents;

    private volatile WakeupDispatcher dispatcher;

    @PostConstruct
    void start() {
        if (!properties.isWakeupEnabled() || !messageBus.enabled() || dispatcher != null) return;
        dispatcher = new WakeupDispatcher(messageBus.messageBus(), this);
        dispatcher.start();
    }

    /**
     * Never skip in the dispatcher. A wakeup entry is ack-on-read, so skipping here would lose it.
     * runWakeup re-acquires the conversation lock and re-enqueues on contention.
     */
    @Override
    public boolean isSessionRunning(String sessionId) {
        return false;
    }

    @Override
    public Mono<Msg> runWakeup(String sessionId) {
        return runWakeup(null, sessionId);
    }

    @Override
    public Mono<Msg> runWakeup(String userId, String sessionId) {
        if (!properties.isWakeupEnabled() || !messageBus.enabled()) return Mono.empty();
        return Mono.fromCallable(() -> executeWakeup(userId, sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.empty());
    }

    private Boolean executeWakeup(String userId, String sessionId) {
        AgentConversation conversation = conversationMapper.selectById(sessionId);
        if (conversation == null || userId == null || !userId.equals(conversation.getUserId())) {
            return null;
        }
        List<AgentAsyncTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getConversationId, sessionId)
                .eq(AgentAsyncTask::getKind, "long-tool")
                .in(AgentAsyncTask::getWakeupStatus, List.of("pending", "enqueued", "running"))
                .orderByAsc(AgentAsyncTask::getCreatedAt)
                .last("LIMIT 20"));
        if (tasks.isEmpty()) return null;

        if (!conversationLock.tryAcquire(sessionId)) {
            retryOrFail(tasks, userId, sessionId);
            return null;
        }
        try {
            markRunning(tasks);
            AgentTurnResult turn = mediaRuntime.runWakeup(conversation);
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                markFailed(tasks, "后台任务已唤醒，但模型没有生成可展示结果");
                return null;
            }
            String wakeupTaskId = tasks.getFirst().getId();
            AgentTurnResult wakeupTurn = withWakeupMetadata(turn, tasks);
            AgentMessage persisted = findExistingWakeupMessage(wakeupTaskId);
            if (persisted == null) {
                persisted = chatService.persistWakeupResult(conversation, wakeupTurn);
            }
            if (persisted == null) {
                markFailed(tasks, "后台任务结果持久化失败");
                return null;
            }
            markConsumed(tasks, persisted.getId());
            taskEvents.publishWakeup(userId, new AgentWakeupMessage(
                    wakeupTaskId, sessionId, persisted.getId()));
            return Boolean.TRUE;
        } catch (Exception e) {
            log.warn("[AgentScope Wakeup] run failed conversationId={}: {}", sessionId, e.getMessage());
            markFailed(tasks, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return null;
        } finally {
            conversationLock.release(sessionId);
        }
    }

    private void retryOrFail(List<AgentAsyncTask> tasks, String userId, String sessionId) {
        int maxAttempts = properties.getWakeupMaxRetries();
        boolean exhausted = true;
        for (AgentAsyncTask task : tasks) {
            int attempts = task.getWakeupAttempts() == null ? 0 : task.getWakeupAttempts();
            taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                    .eq("id", task.getId())
                    .setSql("wakeup_attempts = COALESCE(wakeup_attempts, 0) + 1")
                    .set("updated_at", OffsetDateTime.now()));
            if (attempts + 1 < maxAttempts) exhausted = false;
        }
        if (exhausted) {
            markFailed(tasks, "会话持续占用，Wakeup 重试次数已耗尽");
            return;
        }
        messageBus.messageBus()
                .enqueueWakeup(userId, sessionId, "moon-aigc")
                .delaySubscription(properties.getWakeupRetryDelay())
                .subscribe(
                        ignored -> { },
                        error -> log.warn("[AgentScope Wakeup] retry enqueue failed conversationId={}: {}",
                                sessionId, error.getMessage()));
    }

    private void markRunning(List<AgentAsyncTask> tasks) {
        for (AgentAsyncTask task : tasks) {
            taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                    .eq("id", task.getId())
                    .set("wakeup_status", "running")
                    .set("updated_at", OffsetDateTime.now()));
        }
    }

    private void markConsumed(List<AgentAsyncTask> tasks, String messageId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (AgentAsyncTask task : tasks) {
            taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                    .eq("id", task.getId())
                    .set("wakeup_status", "consumed")
                    .set("wakeup_consumed_at", now)
                    .set("wakeup_message_id", messageId)
                    .set("wakeup_error", null)
                    .set("updated_at", now));
        }
    }

    private void markFailed(List<AgentAsyncTask> tasks, String error) {
        OffsetDateTime now = OffsetDateTime.now();
        for (AgentAsyncTask task : tasks) {
            taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                    .eq("id", task.getId())
                    .set("wakeup_status", "failed")
                    .set("wakeup_error", error)
                    .set("updated_at", now));
        }
    }

    private AgentMessage findExistingWakeupMessage(String wakeupTaskId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
                .apply("metadata->>'wakeupTaskId' = {0}", wakeupTaskId)
                .last("LIMIT 1"));
    }

    private static AgentTurnResult withWakeupMetadata(
            AgentTurnResult turn, List<AgentAsyncTask> tasks) {
        Map<String, Object> metadata = new LinkedHashMap<>(turn.metadata());
        metadata.put("wakeupTaskId", tasks.getFirst().getId());
        metadata.put("wakeupTaskIds", tasks.stream().map(AgentAsyncTask::getId).toList());
        metadata.put("kind", "assistant-reply");
        return new AgentTurnResult(
                turn.content(), metadata, turn.agentTrace(), turn.reasoning(),
                turn.suggestedActions(), turn.hitlPaused(), turn.messageId());
    }

    @PreDestroy
    void close() {
        WakeupDispatcher current = dispatcher;
        dispatcher = null;
        if (current == null) return;
        try {
            current.close();
        } catch (Exception ignored) {
            // Application shutdown.
        }
    }

}
