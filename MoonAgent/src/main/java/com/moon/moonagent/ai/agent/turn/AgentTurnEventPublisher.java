package com.moon.moonagent.ai.agent.turn;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Coalesces high-frequency delta events into strict durable batches.
 * Structural and tool events flush pending deltas first and are persisted immediately.
 */
public final class AgentTurnEventPublisher implements Consumer<AgentEvent>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnEventPublisher.class);
    static final int MAX_BATCH_SIZE = 64;
    static final long FLUSH_INTERVAL_NANOS = 250_000_000L;

    private final AgentTurnEventStore store;
    private final AgentScopeEventMapper mapper;
    private final String turnId;
    private final long contextWindowTokens;
    private final Consumer<AgentTurnEventEnvelope> emitter;
    private final List<AgentTurnEventStore.EventDraft> pending = new ArrayList<>();
    private long lastFlushNanos = System.nanoTime();
    private RuntimeException failure;
    private boolean closed;

    public AgentTurnEventPublisher(AgentTurnEventStore store,
                                   AgentScopeEventMapper mapper,
                                   String turnId,
                                   long contextWindowTokens,
                                   Consumer<AgentTurnEventEnvelope> emitter) {
        this.store = store;
        this.mapper = mapper;
        this.turnId = turnId;
        this.contextWindowTokens = contextWindowTokens;
        this.emitter = emitter;
    }

    @Override
    public synchronized void accept(AgentEvent event) {
        if (closed || failure != null || event == null) return;
        AgentTurnEventStore.EventDraft draft = new AgentTurnEventStore.EventDraft(
                mapper.type(event), mapper.payload(event, contextWindowTokens));
        if (isCoalescible(event)) {
            pending.add(draft);
            long now = System.nanoTime();
            if (pending.size() >= MAX_BATCH_SIZE || now - lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
                flush();
            }
            return;
        }
        flush();
        write(List.of(draft));
    }

    public synchronized void flush() {
        if (pending.isEmpty() || failure != null) return;
        List<AgentTurnEventStore.EventDraft> batch = List.copyOf(pending);
        pending.clear();
        write(batch);
        lastFlushNanos = System.nanoTime();
    }

    private void write(List<AgentTurnEventStore.EventDraft> batch) {
        if (batch.isEmpty() || failure != null) return;
        try {
            for (AgentTurnEventEnvelope envelope : store.appendBatch(turnId, batch)) {
                emitter.accept(envelope);
            }
        } catch (RuntimeException error) {
            failure = error;
            pending.clear();
            log.error("turn event persistence failed turnId={} batchSize={}: {}",
                    turnId, batch.size(), error.getMessage());
        }
    }

    /** Publish an already-normalized AgentScope event through the same ordered sequence. */
    public synchronized void publishRaw(String type, Object payload) {
        if (closed || failure != null) return;
        flush();
        write(List.of(new AgentTurnEventStore.EventDraft(type, payload)));
    }

    private static boolean isCoalescible(AgentEvent event) {
        return event instanceof TextBlockDeltaEvent
                || event instanceof ThinkingBlockDeltaEvent
                || event instanceof ToolCallDeltaEvent
                || event instanceof ToolResultTextDeltaEvent;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        flush();
        if (failure != null) {
            throw new IllegalStateException("turn event persistence failed", failure);
        }
    }
}
