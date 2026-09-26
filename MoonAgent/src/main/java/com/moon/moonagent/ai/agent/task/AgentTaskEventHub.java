package com.moon.moonagent.ai.agent.task;

import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AgentTaskEventHub {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskEventHub.class);
    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        subscribers.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> {
            remove(userId, emitter);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 已关闭的连接无需处理。
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        return emitter;
    }

    public void publish(String userId, AgentTaskView task) {
        List<SseEmitter> targets = subscribers.get(userId);
        if (targets == null || targets.isEmpty()) return;
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event()
                        .name("snapshot")
                        .data(AgentOrchestratorSupport.sseJson(Map.of("task", task))));
            } catch (Exception e) {
                remove(userId, emitter);
                log.debug("agent task SSE subscriber removed: userId={}", userId);
            }
        }
    }

    public void publishWakeup(String userId, AgentWakeupMessage message) {
        List<SseEmitter> targets = subscribers.get(userId);
        if (targets == null || targets.isEmpty()) return;
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event()
                        .name("wakeup_message")
                        .data(AgentOrchestratorSupport.sseJson(message)));
            } catch (Exception e) {
                remove(userId, emitter);
                log.debug("agent wakeup SSE subscriber removed: userId={}", userId);
            }
        }
    }

    private void remove(String userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> targets = subscribers.get(userId);
        if (targets == null) return;
        targets.remove(emitter);
        if (targets.isEmpty()) subscribers.remove(userId, targets);
    }

    @PreDestroy
    void close() {
        subscribers.values().forEach(targets -> targets.forEach(emitter -> {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 进程退出时尽力关闭。
            }
        }));
        subscribers.clear();
    }
}
