package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.AgentTraceEvent;
import com.moon.moonagent.ai.agent.AgentTurnMetrics;
import com.moon.moonagent.ai.agent.SuggestedAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
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
    private volatile Map<String, Object> turnMetadata = Map.of();
    private volatile boolean hitlPaused;
    private volatile boolean terminalEventSent;
    private volatile String hitlMessageId = "";
    private volatile List<SuggestedAction> suggestedActions = List.of();
    private final AgentUsageAccumulator usage = new AgentUsageAccumulator();

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

    public void setTurnMetadata(Map<String, Object> turnMetadata) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(this.turnMetadata);
        if (turnMetadata != null) merged.putAll(turnMetadata);
        this.turnMetadata = java.util.Collections.unmodifiableMap(merged);
    }

    public Map<String, Object> getTurnMetadata() {
        return turnMetadata;
    }

    public void setHitlPaused(boolean hitlPaused) {
        this.hitlPaused = hitlPaused;
    }

    public boolean isHitlPaused() {
        return hitlPaused;
    }

    public void setHitlMessageId(String hitlMessageId) {
        this.hitlMessageId = hitlMessageId == null ? "" : hitlMessageId;
    }

    public String getHitlMessageId() {
        return hitlMessageId;
    }

    public void setSuggestedActions(List<SuggestedAction> suggestedActions) {
        this.suggestedActions = suggestedActions == null ? List.of() : suggestedActions;
    }

    public List<SuggestedAction> getSuggestedActions() {
        return suggestedActions;
    }

    public List<AgentTraceEvent> getEvents() {
        synchronized (events) { return new ArrayList<>(events); }
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    /** 发起一次模型调用；调用失败但未返回 usage 时也会计入分母。 */
    public void beginModelCall() {
        usage.beginCall();
    }

    /** 记录 Spring AI 非流式或流式最终响应的 usage。 */
    public void recordUsage(ChatResponse response) {
        Usage value = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        usage.recordUsage(value);
    }

    /** 记录已经提取出的 usage，供非 Spring AI 运行时复用。 */
    public void recordUsage(long inputTokens, long outputTokens, long cachedInputTokens) {
        usage.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    /** 当前执行段的不可变指标快照。 */
    public AgentTurnMetrics turnMetrics() {
        long durationMs = Duration.between(startedAt, OffsetDateTime.now()).toMillis();
        return usage.snapshot(durationMs);
    }

    /** 给终态 SSE payload 注入统一指标；重复调用不会重复累计。 */
    public Map<String, Object> enrichTerminalEvent(Map<String, Object> payload) {
        terminalEventSent = true;
        Map<String, Object> enriched = new java.util.LinkedHashMap<>(
                payload == null ? Map.of() : payload);
        enriched.putIfAbsent("metrics", turnMetrics());
        return enriched;
    }

    public boolean isTerminalEventSent() {
        return terminalEventSent;
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
                        .data(AgentOrchestratorSupport.sseJson(Map.of("traceEvent", event))));
            }
        } catch (Exception e) {
            log.debug("agent_trace 发送失败（前端可能已断开）: {}",
                    request.getConversation() == null ? "" : request.getConversation().getId());
        }
    }
}
