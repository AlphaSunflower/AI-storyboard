package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.AgentTraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一轮编排的轨迹累积器：持有请求、事件列表与开始时间，负责：
 * <ul>
 *   <li>{@link #start} / {@link #end}：记录阶段开始/结束并通过 SSE {@code agent_trace} 下发；</li>
 *   <li>{@link #add}：单条轨迹事件直接下发；</li>
 *   <li>{@link #reasoning}：保存推理摘要快照，供 turn metadata 落库。</li>
 * </ul>
 *
 * <p>安全约束：轨迹数据只包含阶段/意图/状态等元信息，<b>不暴露原始 prompt</b>。
 * 数据统一使用 {@link AgentOrchestratorSupport#sseJson} 序列化。
 */
public class AgentTraceAccumulator {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceAccumulator.class);

    private final OrchestrationRequest request;
    private final List<AgentTraceEvent> events = new ArrayList<>();
    private final Map<String, OffsetDateTime> pendingStart = new ConcurrentHashMap<>();
    private final OffsetDateTime startedAt;
    private volatile AgentReasoningSnapshot reasoning;

    public AgentTraceAccumulator(OrchestrationRequest request) {
        this.request = request;
        this.startedAt = OffsetDateTime.now();
    }

    /** 开始一个阶段：生成事件并立即下发 {@code agent_trace}（status=node_started），返回事件 id。 */
    public String start(String phase, String specialist, String title, String currentIntent) {
        String id = UUID.randomUUID().toString();
        AgentTraceEvent event = new AgentTraceEvent(
                id, phase, "moon", specialist == null ? "" : specialist,
                "node_started", title, "", currentIntent == null ? "" : currentIntent, "",
                OffsetDateTime.now(), null);
        pendingStart.put(id, event.startedAt());
        synchronized (events) { events.add(event); }
        send(event);
        return id;
    }

    /** 结束一个阶段：回填 status / summary / durationMs，通过 SSE 更新下发。 */
    public void end(String id, String status, String summary) {
        if (id == null || id.isBlank()) return;
        AgentTraceEvent target = find(id);
        if (target == null) return;
        OffsetDateTime started = pendingStart.remove(id);
        Long durationMs = started == null ? null : Duration.between(started, OffsetDateTime.now()).toMillis();
        AgentTraceEvent updated = new AgentTraceEvent(
                target.id(), target.phase(), target.agent(), target.specialist(),
                status == null ? "node_completed" : status,
                target.title(), summary == null ? target.summary() : summary,
                target.currentIntent(), target.targetIntent(),
                target.startedAt(), durationMs);
        synchronized (events) {
            events.replaceAll(e -> id.equals(e.id()) ? updated : e);
        }
        send(updated);
    }

    /** 单条轨迹事件：加入列表并立即下发。 */
    public void add(AgentTraceEvent event) {
        if (event == null) return;
        synchronized (events) { events.add(event); }
        send(event);
    }

    /** 简便入口：直接记录一条一次性轨迹（非 start/end 配对）。 */
    public void add(String phase, String specialist, String title, String summary,
                    String currentIntent, String targetIntent, String status) {
        add(new AgentTraceEvent(UUID.randomUUID().toString(), phase, "moon",
                specialist == null ? "" : specialist, status == null ? "trace" : status,
                title, summary, currentIntent == null ? "" : currentIntent,
                targetIntent == null ? "" : targetIntent,
                OffsetDateTime.now(), null));
    }

    /** 保存推理摘要快照（供 metadata 落库）。 */
    public void reasoning(AgentReasoningSnapshot snapshot) {
        if (snapshot == null) return;
        this.reasoning = snapshot;
    }

    public AgentReasoningSnapshot getReasoning() {
        return reasoning;
    }

    public List<AgentTraceEvent> getEvents() {
        synchronized (events) { return new ArrayList<>(events); }
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    /** 处理完成：以 route_change 语义汇总所有事件（供调用方落库 metadata）。 */
    public List<AgentTraceEvent> snapshot() {
        return getEvents();
    }

    private AgentTraceEvent find(String id) {
        synchronized (events) {
            return events.stream().filter(e -> id.equals(e.id())).findFirst().orElse(null);
        }
    }

    private void send(AgentTraceEvent event) {
        try {
            SseEmitter emitter = request.getEmitter();
            if (emitter != null) {
                emitter.send(SseEmitter.event().name("agent_trace")
                        .data(AgentOrchestratorSupport.sseJson(Map.of("event", event))));
            }
        } catch (Exception e) {
            log.debug("agent_trace 发送失败（前端可能已断开）: {}",
                    request.getConversation() == null ? "" : request.getConversation().getId());
        }
    }
}
