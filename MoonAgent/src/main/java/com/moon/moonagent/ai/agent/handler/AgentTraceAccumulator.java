package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.AgentTraceEvent;
import com.moon.moonagent.ai.agent.AgentTurnMetrics;
import com.moon.moonagent.ai.agent.AgentContextWindowResolver;
import com.moon.moonagent.ai.agent.SuggestedAction;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 一轮编排的轨迹累积器：持有请求、事件列表与开始时间，负责：
 * <ul>
 *   <li>{@link #start} / {@link #end}：记录阶段开始/结束并通过 SSE {@code agent_trace} 下发；</li>
 *   <li>{@link #add}：单条轨迹事件直接下发；</li>
 *   <li>{@link #reasoning}：保存推理摘要快照，供 turn metadata 落库。</li>
 * </ul>
 *
 * <p>安全约束：轨迹数据只包含阶段/意图/状态等元信息，<b>不暴露原始 prompt</b>。
 * 数据统一使用 {@link AgentRuntimeSupport#sseJson} 序列化。
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
    private volatile Consumer<AgentTraceEvent> eventSink;
    private final StringBuilder thinkingDetails = new StringBuilder();
    /**
     * 本轮已经推送给前端的可见正文增量。
     *
     * <p>用户中途打断（「停止生成」/「打断并发送」）时本轮不会产出 {@code AgentTurnResult}，
     * 前端直播到的这段文字会随流一起消失：刷新后既看不到、也不再进入会话上下文。
     * 因此这里按与前端 {@code TEXT_BLOCK_DELTA} 完全相同的口径累积，供打断收口时落库。</p>
     */
    private final StringBuilder assistantText = new StringBuilder();
    /**
     * 本轮工具调用草稿：key = toolCallId，与前端 `generating.toolDrafts` 同构。
     *
     * <p>用 {@link LinkedHashMap} 保住首次出现顺序——前端 `orderedToolDrafts` 在缺 `seq` 时
     * 正是回落到插入顺序，因此落库后刷新渲染的顺序与直播一致。
     */
    private final Map<String, Map<String, Object>> toolDrafts = new LinkedHashMap<>();
    private volatile OffsetDateTime thinkingStartedAt;
    private volatile OffsetDateTime thinkingEndedAt;
    private final AgentUsageAccumulator usage = new AgentUsageAccumulator();
    private final int contextWindowTokens;
    /** 当前上下文组成估算：系统提示词 / 工具定义（每轮开始时由运行时写入）。 */
    private volatile long systemPromptTokens;
    private volatile long toolDefinitionTokens;

    public AgentTraceAccumulator(OrchestrationRequest request) {
        this(request, AgentContextWindowResolver.DEFAULT_WINDOW_TOKENS);
    }

    /**
     * @param contextWindowTokens 本轮模型的上下文窗口大小（由 {@link AgentContextWindowResolver} 解析后传入）。
     */
    public AgentTraceAccumulator(OrchestrationRequest request, int contextWindowTokens) {
        this.request = request;
        this.startedAt = OffsetDateTime.now();
        this.contextWindowTokens = Math.max(0, contextWindowTokens);
    }

    /** 本轮模型的上下文窗口大小（token）。 */
    public int contextWindowTokens() {
        return contextWindowTokens;
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

    /** AgentScope 原生思考增量可能在结束后才发现没有 Snapshot；这里保留可落库副本。 */
    public synchronized void recordThinkingStart() {
        if (thinkingDetails.length() > 0
                && !thinkingDetails.toString().endsWith("\n\n")) {
            thinkingDetails.append("\n\n");
        }
        if (thinkingStartedAt == null) thinkingStartedAt = OffsetDateTime.now();
        thinkingEndedAt = null;
    }

    public synchronized void recordThinkingDelta(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (thinkingStartedAt == null) thinkingStartedAt = OffsetDateTime.now();
        thinkingDetails.append(delta);
        thinkingEndedAt = null;
    }

    /** 记录一段已经下发给前端的可见正文增量（与前端打字机同源）。 */
    public synchronized void recordAssistantDelta(String delta) {
        if (delta == null || delta.isEmpty()) return;
        assistantText.append(delta);
    }

    /** 本轮打断时已经流出的可见正文（无正文时为空白）。 */
    public synchronized String assistantText() {
        return assistantText.toString();
    }

    public synchronized void recordThinkingEnd() {
        if (thinkingDetails.length() > 0 && thinkingEndedAt == null) {
            thinkingEndedAt = OffsetDateTime.now();
        }
    }

    public synchronized AgentReasoningSnapshot reasoningSnapshot() {
        if (thinkingDetails.length() == 0) return reasoning;
        String details = thinkingDetails.toString();
        String summary = lastCompletedSentence(details);
        OffsetDateTime endedAt = thinkingEndedAt == null ? OffsetDateTime.now() : thinkingEndedAt;
        long durationMs = thinkingStartedAt == null
                ? 0L : Math.max(0L, Duration.between(thinkingStartedAt, endedAt).toMillis());
        return new AgentReasoningSnapshot(summary, details, durationMs, "agentscope");
    }

    /**
     * 记录一条 AgentScope 工具事件，累积成本轮的工具调用草稿。
     *
     * <p>为什么后端也要累积：`TOOL_*` 事件目前只由前端从原生 `agent_event` 派生，
     * 后端从不落库 `toolDrafts`（只有 `sandboxAttachments` 会写 metadata），
     * 于是刷新后工具调用明细必然消失（前端 `ToolResultBlocks` 只有直播态 `generating.toolDrafts` 可读）。
     * 这里按与前端**完全相同的字段语义**累积，由调用方并进 `AgentTurnResult.metadata()`，
     * 前端既有的 `metadata.toolDrafts` 回放路径即可零改动生效。
     *
     * <p>字段语义对齐前端 `toolStatus()`：TOOL_CALL_START→node_started、
     * TOOL_RESULT_START→node_running、TOOL_RESULT_END 按 state 判 node_completed/node_failed。
     */
    public void recordToolEvent(AgentEvent event) {
        if (event == null) return;
        if (event instanceof ToolCallStartEvent value) {
            update(value.getToolCallId(), value.getToolCallName(), null, null, "node_started");
        } else if (event instanceof ToolCallDeltaEvent value) {
            update(value.getToolCallId(), value.getToolCallName(), value.getDelta(), null, "node_running");
        } else if (event instanceof ToolResultStartEvent value) {
            update(value.getToolCallId(), value.getToolCallName(), null, null, "node_running");
        } else if (event instanceof ToolResultTextDeltaEvent value) {
            update(value.getToolCallId(), value.getToolCallName(), null, value.getDelta(), "node_running");
        } else if (event instanceof ToolResultEndEvent value) {
            String state = value.getState() == null ? "" : value.getState().name().toUpperCase(java.util.Locale.ROOT);
            String status = failedToolState(state) ? "node_failed" : "node_completed";
            update(value.getToolCallId(), value.getToolCallName(), null, null, status);
        }
    }

    /**
     * 终态是否算失败——与前端 `toolStatus()` 同一口径。
     *
     * <p>{@code ToolResultState} 的真实取值是 {@code SUCCESS / ERROR / INTERRUPTED / DENIED / RUNNING}，
     * 只判 {@code FAIL}/{@code DENIED} 会把 {@code ERROR} 记成 {@code node_completed}（失败显示成"已完成"）。
     */
    private static boolean failedToolState(String state) {
        return state.contains("FAIL") || state.contains("ERROR")
                || state.contains("DENIED") || state.contains("INTERRUPT");
    }

    /** 本轮工具草稿快照（无工具调用时返回空 Map，调用方据此决定是否写 metadata）。 */
    public Map<String, Object> toolDrafts() {
        synchronized (toolDrafts) {
            return toolDrafts.isEmpty() ? Map.of() : new LinkedHashMap<>(toolDrafts);
        }
    }

    @SuppressWarnings("unchecked")
    private void update(String toolCallId, String toolName, String inputDelta,
                        String textDelta, String status) {
        if (toolCallId == null || toolCallId.isBlank()) return;
        synchronized (toolDrafts) {
            Map<String, Object> draft = (Map<String, Object>) toolDrafts.get(toolCallId);
            if (draft == null) {
                draft = new LinkedHashMap<>();
                draft.put("name", toolName == null ? "" : toolName);
                draft.put("text", "");
                draft.put("input", "");
                draft.put("status", status);
                // 前端用 startedAt 只做 UI 排序，这里给同一语义的毫秒时间戳
                draft.put("startedAt", System.currentTimeMillis());
                toolDrafts.put(toolCallId, draft);
            }
            if (toolName != null && !toolName.isBlank()) draft.put("name", toolName);
            if (inputDelta != null && !inputDelta.isEmpty()) {
                draft.put("input", String.valueOf(draft.get("input")) + inputDelta);
            }
            if (textDelta != null && !textDelta.isEmpty()) {
                draft.put("text", String.valueOf(draft.get("text")) + textDelta);
            }
            draft.put("status", status);
        }
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

    /** Register an additional live sink used by the sequenced AgentScope transport. */
    public void setEventSink(Consumer<AgentTraceEvent> eventSink) {
        this.eventSink = eventSink;
    }

    public boolean hasEventSink() {
        return eventSink != null;
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

    /** 记录一次模型调用的 token 用量与该次推理耗时（毫秒）。 */
    public void recordUsage(long inputTokens, long outputTokens, long cachedInputTokens,
                            long modelDurationMs) {
        usage.recordUsage(inputTokens, outputTokens, cachedInputTokens);
        usage.addModelDuration(modelDurationMs);
    }

    /** 工具开始执行（ToolResult 开始）。 */
    public void beginToolCall(String toolCallId) {
        usage.beginToolCall(toolCallId);
    }

    /** 工具执行结束（ToolResult 结束）：累计该次执行耗时。 */
    public void endToolCall(String toolCallId) {
        usage.endToolCall(toolCallId);
    }

    /**
     * 设置本轮「上下文组成」估算：系统提示词与工具定义 token。
     * 对话消息量在快照时由「上下文占用 − 系统 − 工具」推出。
     */
    public void setContextBreakdown(long systemPromptTokens, long toolDefinitionTokens) {
        this.systemPromptTokens = Math.max(0L, systemPromptTokens);
        this.toolDefinitionTokens = Math.max(0L, toolDefinitionTokens);
    }

    /** 当前执行段的不可变指标快照。 */
    public AgentTurnMetrics turnMetrics() {
        long durationMs = Duration.between(startedAt, OffsetDateTime.now()).toMillis();
        return usage.snapshot(durationMs, contextWindowTokens, systemPromptTokens, toolDefinitionTokens);
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

    private static String lastCompletedSentence(String details) {
        if (details == null || details.isBlank()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[^。．.!?！？\\n]+[。．.!?！？]")
                .matcher(details);
        String last = "";
        while (matcher.find()) last = matcher.group().trim();
        return last;
    }

    private void send(AgentTraceEvent event) {
        Consumer<AgentTraceEvent> sink = eventSink;
        if (sink != null) {
            try {
                sink.accept(event);
            } catch (Exception e) {
                log.debug("agent_trace sink 发送失败: {}", e.getMessage());
            }
            return;
        }
        try {
            SseEmitter emitter = request.getEmitter();
            if (emitter != null) {
                emitter.send(SseEmitter.event().name("agent_trace")
                        .data(AgentRuntimeSupport.sseJson(Map.of("traceEvent", event))));
            }
        } catch (Exception e) {
            log.debug("agent_trace 发送失败（前端可能已断开）: {}",
                    request.getConversation() == null ? "" : request.getConversation().getId());
        }
    }
}
