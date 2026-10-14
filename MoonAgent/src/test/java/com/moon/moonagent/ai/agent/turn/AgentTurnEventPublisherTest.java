package com.moon.moonagent.ai.agent.turn;

import com.moon.moonagent.ai.agent.AgentTraceEvent;
import com.moon.moonagent.entity.AgentTurnEventEntity;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentTurnEventPublisher 回归测试：确认它是 turn 事件唯一的「写入 + 推送」有序入口。
 *
 * <p>覆盖红线 1 的三个不变量：</p>
 * <ol>
 *   <li>delta 与结构/生命周期事件共用同一序号序列，且 merge 后前端看到的 seq 连续无缺口；</li>
 *   <li>持久化失败必须显式暴露（close 抛出），失败之后不得再产生后续序号（不静默跳号）；</li>
 *   <li>通过 publisher 记录的事件与 afterSeq 补偿读取到的事件按 (seq,type,payload) 完全一致。</li>
 * </ol>
 */
class AgentTurnEventPublisherTest {

    private static final String TURN_ID = "turn-1";
    private static final long CONTEXT_WINDOW = 128_000L;

    @Test
    void lifecycleEventInterleavedWithPendingDeltasKeepsSequenceContiguous() {
        Fixture fixture = new Fixture();

        fixture.publisher.accept(new TextBlockDeltaEvent(null, "reply-1", "block-1", "你", null));
        fixture.publisher.accept(new ThinkingBlockDeltaEvent(null, "reply-1", "block-2", "想", null));

        // 结构事件：必须先 flush 未落库的 delta，再写自己 —— seq 不能出现空洞
        fixture.publisher.publish("turn_completed", Map.of("status", "completed"));

        assertThat(fixture.sequences()).containsExactly(1, 2, 3);
        assertThat(fixture.emittedTypes()).containsExactly(
                "TEXT_BLOCK_DELTA", "THINKING_BLOCK_DELTA", "turn_completed");
        assertThat(fixture.firstBatchSize()).isEqualTo(2);
        assertThat(fixture.emittedSequences()).containsExactly(1, 2, 3);
    }

    @Test
    void pendingDeltaIsNeverOvertakenByATerminalEvent() {
        Fixture fixture = new Fixture();

        fixture.publisher.accept(new TextBlockDeltaEvent(null, "reply-1", "block-1", "a", null));
        fixture.publisher.accept(new TextBlockDeltaEvent(null, "reply-1", "block-1", "b", null));
        fixture.publisher.publish("turn_failed", Map.of("code", "50001", "message", "boom"));

        assertThat(fixture.emittedTypes())
                .containsExactly("TEXT_BLOCK_DELTA", "TEXT_BLOCK_DELTA", "turn_failed");
        assertThat(fixture.emittedSequences()).containsExactly(1, 2, 3);
    }

    @Test
    void persistenceFailureLatchesLoudlyAndNeverContinuesWithLaterSequences() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        AgentScopeEventMapper mapper = mapper();
        List<AgentTurnEventEnvelope> emitted = new ArrayList<>();
        AgentTurnEventPublisher publisher = new AgentTurnEventPublisher(
                store, mapper, TURN_ID, CONTEXT_WINDOW, emitted::add);
        when(store.appendBatch(eq(TURN_ID), any())).thenThrow(
                new IllegalStateException("agent_turn_events insert failed"));

        publisher.publish("turn_started", Map.of("turnId", TURN_ID));
        publisher.publish("turn_failed", Map.of("code", "50001", "message", "boom"));

        // 失败必须显式暴露，不能静默吞掉后继续
        assertThatThrownBy(publisher::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turn event persistence failed")
                .hasRootCauseMessage("agent_turn_events insert failed");
        // 闩锁后不得再产生任何后续序号（乱序续写会制造永久 seq 缺口，前端会一直等待）
        verify(store).appendBatch(eq(TURN_ID), any());
        assertThat(emitted).isEmpty();
    }

    @Test
    void replayAfterSeqReturnsExactlyTheEventsThePublisherWrote() {
        Fixture fixture = new Fixture();
        AgentTraceEvent traceEvent = new AgentTraceEvent(
                "trace-1", "model_reasoning", "moon", "agentscope",
                "node_started", "模型推理", "", "", "",
                OffsetDateTime.parse("2026-10-10T08:00:00Z"), null);

        fixture.publisher.publish("turn_started", Map.of("turnId", TURN_ID));
        fixture.publisher.accept(new TextBlockDeltaEvent(null, "reply-1", "block-1", "实时", null));
        fixture.publisher.publishRaw("agent_event", Map.of(
                "eventType", "AGENT_TRACE",
                "data", Map.of("traceEvent", traceEvent)));
        fixture.publisher.publish("turn_completed", Map.of("status", "completed"));
        fixture.publisher.close();

        List<AgentTurnEventEnvelope> live = fixture.emitted;
        List<AgentTurnEventEnvelope> compensated = fixture.store.eventsAfter(TURN_ID, 0);

        assertThat(live).extracting(AgentTurnEventEnvelope::seq).containsExactly(1, 2, 3, 4);
        assertThat(compensated).hasSameSizeAs(live);
        for (int index = 0; index < live.size(); index++) {
            AgentTurnEventEnvelope liveEvent = live.get(index);
            AgentTurnEventEnvelope replayed = compensated.get(index);
            assertThat(replayed.seq()).isEqualTo(liveEvent.seq());
            assertThat(replayed.type()).isEqualTo(liveEvent.type());
            // 线上契约是 payloadJson（sendRuntimeEnvelope 优先用它）：必须逐字节一致
            assertThat(replayed.payloadJson()).isEqualTo(liveEvent.payloadJson());
            // 补偿侧 payload 是反序列化结果，与直播侧的原始对象图按同一份 JSON 归一后比较
            assertThat(AgentTurnEventStoreTestSupport.toJson(replayed.payload()))
                    .isEqualTo(AgentTurnEventStoreTestSupport.toJson(liveEvent.payload()));
        }
        assertThat(compensated.get(2).payloadJson())
                .contains("\"startedAt\"")
                .doesNotContain("\"data\":{}");
    }

    private static AgentScopeEventMapper mapper() {
        return new AgentScopeEventMapper();
    }

    /** store 替身：按 appendBatch 的调用顺序分配连续 seq，并保留与真实 store 相同的事件内容。 */
    private static final class Fixture {
        private final Map<Integer, AgentTurnEventEntity> persisted = new LinkedHashMap<>();
        private final List<AgentTurnEventEnvelope> emitted = new ArrayList<>();
        private final AgentTurnEventStore store;
        private final AgentTurnEventPublisher publisher;
        private final List<Integer> batchSizes = new ArrayList<>();

        private Fixture() {
            AtomicInteger lastSequence = new AtomicInteger();
            store = mock(AgentTurnEventStore.class);
            when(store.appendBatch(eq(TURN_ID), any())).thenAnswer(invocation ->
                    append(invocation.getArgument(1), lastSequence));
            when(store.eventsAfter(eq(TURN_ID), anyInt())).thenAnswer(invocation -> {
                int afterSequence = invocation.getArgument(1);
                List<AgentTurnEventEnvelope> replay = new ArrayList<>();
                for (Map.Entry<Integer, AgentTurnEventEntity> entry : persisted.entrySet()) {
                    if (entry.getKey() <= afterSequence) continue;
                    AgentTurnEventEntity entity = entry.getValue();
                    replay.add(new AgentTurnEventEnvelope(
                            TURN_ID, entity.getSequence(), entity.getEventType(),
                            AgentTurnEventStoreTestSupport.fromJson(entity.getPayload()),
                            entity.getPayload()));
                }
                return List.copyOf(replay);
            });
            publisher = new AgentTurnEventPublisher(
                    store, mapper(), TURN_ID, CONTEXT_WINDOW, emitted::add);
        }

        @SuppressWarnings("unchecked")
        private List<AgentTurnEventEnvelope> append(
                List<AgentTurnEventStore.EventDraft> drafts, AtomicInteger lastSequence) {
            batchSizes.add(drafts.size());
            List<AgentTurnEventEnvelope> envelopes = new ArrayList<>(drafts.size());
            for (AgentTurnEventStore.EventDraft draft : drafts) {
                int sequence = lastSequence.incrementAndGet();
                String payloadJson = AgentTurnEventStoreTestSupport.toJson(draft.payload());
                AgentTurnEventEntity entity = new AgentTurnEventEntity();
                entity.setTurnId(TURN_ID);
                entity.setSequence(sequence);
                entity.setEventType(draft.type());
                entity.setPayload(payloadJson);
                entity.setCreatedAt(OffsetDateTime.now());
                persisted.put(sequence, entity);
                envelopes.add(new AgentTurnEventEnvelope(
                        TURN_ID, sequence, draft.type(),
                        AgentTurnEventStoreTestSupport.payloadMap(draft.payload()), payloadJson));
            }
            return List.copyOf(envelopes);
        }

        private List<Integer> sequences() {
            return persisted.keySet().stream().toList();
        }

        private List<String> emittedTypes() {
            return emitted.stream().map(AgentTurnEventEnvelope::type).toList();
        }

        private List<Integer> emittedSequences() {
            return emitted.stream().map(AgentTurnEventEnvelope::seq).toList();
        }

        private int firstBatchSize() {
            return batchSizes.getFirst();
        }
    }
}
