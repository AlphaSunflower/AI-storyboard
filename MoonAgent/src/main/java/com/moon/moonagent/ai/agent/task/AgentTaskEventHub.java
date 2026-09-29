package com.moon.moonagent.ai.agent.task;

import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class AgentTaskEventHub {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskEventHub.class);
    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    /** 心跳间隔：既防代理按空闲超时掐断，也让已死连接在写失败时被及时摘除。 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15L;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "agent-task-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public AgentTaskEventHub() {
        heartbeat.scheduleWithFixedDelay(this::beat,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void beat() {
        subscribers.forEach((userId, targets) -> {
            for (SseEmitter emitter : targets) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    drop(userId, emitter, "heartbeat");
                }
            }
        });
    }

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
                        .data(AgentRuntimeSupport.sseJson(Map.of("task", task))));
            } catch (Exception e) {
                drop(userId, emitter, "snapshot");
            }
        }
    }

    public void publishWakeup(String userId, AgentWakeupMessage message) {
        send(userId, "wakeup_message", message);
    }

    /** 任务运行期的实时输出/思考链（任务面板流式展示）。 */
    public void publishStream(String userId, String taskId, long seq, String stage,
                              String text, String thinking, boolean done,
                              java.time.OffsetDateTime updatedAt) {
        send(userId, "task_stream", Map.of(
                "taskId", taskId == null ? "" : taskId,
                "seq", seq,
                "stage", stage == null ? "" : stage,
                "text", text == null ? "" : text,
                "thinking", thinking == null ? "" : thinking,
                "done", done,
                "updatedAt", updatedAt == null ? "" : updatedAt.toString()));
    }

    private void send(String userId, String eventName, Object payload) {
        List<SseEmitter> targets = subscribers.get(userId);
        if (targets == null || targets.isEmpty()) return;
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(AgentRuntimeSupport.sseJson(payload)));
            } catch (Exception e) {
                drop(userId, emitter, eventName);
            }
        }
    }

    /**
     * 发送失败时除了摘掉订阅，还必须关闭响应：
     * 否则浏览器那条 SSE 会一直挂着（无数据也不报错），前端永远不触发重连，实时输出就此卡死。
     */
    private void drop(String userId, SseEmitter emitter, String eventName) {
        remove(userId, emitter);
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已经关闭的连接无需处理
        }
        log.debug("agent task SSE subscriber dropped: userId={}, event={}", userId, eventName);
    }

    private void remove(String userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> targets = subscribers.get(userId);
        if (targets == null) return;
        targets.remove(emitter);
        if (targets.isEmpty()) subscribers.remove(userId, targets);
    }

    @PreDestroy
    void close() {
        heartbeat.shutdownNow();
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
