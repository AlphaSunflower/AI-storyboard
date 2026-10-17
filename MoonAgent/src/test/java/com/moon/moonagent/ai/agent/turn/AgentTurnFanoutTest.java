package com.moon.moonagent.ai.agent.turn;

import com.moon.moonagent.entity.AgentTurnEntity;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentTurnFanout 回归测试：刷新/重连的订阅连接必须"先补历史、再进直播"，且绝不影响 publisher 主流程。
 *
 * <p>覆盖四条不变量：</p>
 * <ol>
 *   <li>注册补历史与并发 publish 之间没有窗口（历史 + 直播交叉时不丢事件、不乱序）；</li>
 *   <li>按 seq 去重：历史里已经补过的事件不会因随后的广播重复投递；</li>
 *   <li>终态 complete 之后订阅者被清理并关闭，且之后注册不会挂住；</li>
 *   <li>发送失败 / 队列背压超限只摘除该订阅者，不影响其他订阅者，异常不外泄。</li>
 * </ol>
 */
class AgentTurnFanoutTest {

    private static final String TURN_ID = "turn-1";

    @Test
    void registerReplaysHistoryAndKeepsReceivingLiveEventsInOrder() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        when(store.eventsAfter(eq(TURN_ID), anyInt()))
                .thenReturn(List.of(envelope(1, "turn_started"), envelope(2, "agent_event")));
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);
        RecordingEmitter emitter = new RecordingEmitter();

        fanout.register(TURN_ID, emitter, 0);
        fanout.publish(TURN_ID, envelope(3, "agent_event"));

        assertThat(emitter.sequences()).containsExactly(1, 2, 3);
        assertThat(emitter.types()).containsExactly("turn_started", "agent_event", "agent_event");
        assertThat(fanout.subscriberCount(TURN_ID)).isEqualTo(1);
    }

    @Test
    void liveEventPublishedWhileRegisteringIsNotLostNorDuplicated() throws Exception {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        CountDownLatch historyReading = new CountDownLatch(1);
        CountDownLatch releaseHistory = new CountDownLatch(1);
        when(store.eventsAfter(eq(TURN_ID), anyInt())).thenAnswer(invocation -> {
            historyReading.countDown();
            // 卡住 register（它此刻持有 per-turn 锁），让 publish 去抢同一把锁。
            releaseHistory.await(5, TimeUnit.SECONDS);
            return List.of(envelope(1, "turn_started"), envelope(2, "agent_event"));
        });
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);
        RecordingEmitter emitter = new RecordingEmitter();

        Thread registering = new Thread(() -> fanout.register(TURN_ID, emitter, 0), "register");
        registering.start();
        assertThat(historyReading.await(5, TimeUnit.SECONDS)).isTrue();

        // 与注册交叉的直播事件：必须在补完历史之后投递，不能插队也不能丢。
        Thread publishing = new Thread(
                () -> fanout.publish(TURN_ID, envelope(3, "agent_event")), "publish");
        publishing.start();
        releaseHistory.countDown();
        registering.join(5_000);
        publishing.join(5_000);

        assertThat(emitter.sequences()).containsExactly(1, 2, 3);
        assertThat(fanout.subscriberCount(TURN_ID)).isEqualTo(1);
    }

    @Test
    void alreadyDeliveredSequencesAreSkippedOnReplayAndBroadcast() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        when(store.eventsAfter(eq(TURN_ID), anyInt()))
                .thenReturn(List.of(envelope(1, "turn_started"), envelope(2, "agent_event")));
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);
        RecordingEmitter emitter = new RecordingEmitter();

        // 客户端已经收到 seq 1：只补 2，且随后的广播不能把 2 再发一次。
        fanout.register(TURN_ID, emitter, 1);
        fanout.publish(TURN_ID, envelope(2, "agent_event"));
        fanout.publish(TURN_ID, envelope(3, "agent_event"));

        assertThat(emitter.sequences()).containsExactly(2, 3);
    }

    @Test
    void terminalHistoryClosesTheSubscriptionWithoutWaitingForLiveEvents() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        when(store.eventsAfter(eq(TURN_ID), anyInt()))
                .thenReturn(List.of(envelope(1, "turn_started"), envelope(2, "turn_completed")));
        when(store.find(TURN_ID)).thenReturn(turn("completed"));
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);
        RecordingEmitter emitter = new RecordingEmitter();

        fanout.register(TURN_ID, emitter, 0);

        assertThat(emitter.sequences()).containsExactly(1, 2);
        assertThat(emitter.isCompleted()).isTrue();
        assertThat(fanout.subscriberCount(TURN_ID)).isZero();
    }

    @Test
    void pausedTurnHistoryKeepsSubscriptionWhenTheSameTurnWasAlreadyResumed() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        // 历史末尾仍是上一段的 turn_paused，但该 turnId 已被 resume 回到 running（HITL 续跑复用同一 turnId）。
        when(store.eventsAfter(eq(TURN_ID), anyInt()))
                .thenReturn(List.of(envelope(1, "turn_paused")));
        when(store.find(TURN_ID)).thenReturn(turn("running"));
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);
        RecordingEmitter emitter = new RecordingEmitter();

        fanout.register(TURN_ID, emitter, 0);
        fanout.publish(TURN_ID, envelope(2, "agent_event"));

        assertThat(emitter.sequences()).containsExactly(1, 2);
        assertThat(emitter.isCompleted()).isFalse();
        assertThat(fanout.subscriberCount(TURN_ID)).isEqualTo(1);
    }

    @Test
    void completeClosesAndRemovesSubscribersAndLateRegisterDoesNotHang() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        when(store.eventsAfter(eq(TURN_ID), anyInt()))
                .thenReturn(List.of(envelope(1, "turn_started")));
        when(store.find(TURN_ID)).thenReturn(turn("completed"));
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);
        RecordingEmitter first = new RecordingEmitter();
        fanout.register(TURN_ID, first, 0);
        assertThat(fanout.subscriberCount(TURN_ID)).isEqualTo(1);

        fanout.complete(TURN_ID);

        assertThat(first.isCompleted()).isTrue();
        assertThat(fanout.subscriberCount(TURN_ID)).isZero();
        assertThat(fanout.isClosed(TURN_ID)).isTrue();

        // 收尾之后才注册（"查到 running → turn 刚好结束"的竞态）：补完历史即关闭，绝不挂住。
        RecordingEmitter late = new RecordingEmitter();
        fanout.register(TURN_ID, late, 0);
        assertThat(late.sequences()).containsExactly(1);
        assertThat(late.isCompleted()).isTrue();
        assertThat(fanout.subscriberCount(TURN_ID)).isZero();
    }

    @Test
    void pausedTurnLeavesNoTombstoneSoTheResumedTurnCanBeSubscribedAgain() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        when(store.eventsAfter(eq(TURN_ID), anyInt()))
                .thenReturn(List.of(envelope(1, "turn_paused")));
        // 暂停收尾时状态是 waiting_user，之后 resume 会复用同一 turnId。
        when(store.find(TURN_ID)).thenReturn(turn("waiting_user"));
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);
        RecordingEmitter first = new RecordingEmitter();
        fanout.register(TURN_ID, first, 0);
        // 历史末尾是 turn_paused 且不在 running：按"本次直播已结束"处理，补完即关闭。
        assertThat(first.isCompleted()).isTrue();

        fanout.complete(TURN_ID);
        assertThat(fanout.isClosed(TURN_ID)).isFalse();

        // resume 开始后（状态回到 running）刷新订阅：必须还能进直播。
        when(store.eventsAfter(eq(TURN_ID), anyInt()))
                .thenReturn(List.of(envelope(1, "turn_paused"), envelope(2, "turn_started")));
        when(store.find(TURN_ID)).thenReturn(turn("running"));
        RecordingEmitter resumed = new RecordingEmitter();
        fanout.register(TURN_ID, resumed, 1);
        fanout.publish(TURN_ID, envelope(3, "agent_event"));

        assertThat(resumed.sequences()).containsExactly(2, 3);
        assertThat(resumed.isCompleted()).isFalse();
    }

    @Test
    void unregisterOnlyRemovesThatSubscriber() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        when(store.eventsAfter(eq(TURN_ID), anyInt())).thenReturn(List.of());
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);
        RecordingEmitter first = new RecordingEmitter();
        RecordingEmitter second = new RecordingEmitter();
        fanout.register(TURN_ID, first, 0);
        fanout.register(TURN_ID, second, 0);

        fanout.unregister(TURN_ID, first);
        fanout.publish(TURN_ID, envelope(1, "agent_event"));

        assertThat(fanout.subscriberCount(TURN_ID)).isEqualTo(1);
        assertThat(first.sequences()).isEmpty();
        assertThat(second.sequences()).containsExactly(1);
    }

    @Test
    void failingSubscriberIsRemovedAndOtherSubscribersKeepReceiving() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        when(store.eventsAfter(eq(TURN_ID), anyInt())).thenReturn(List.of());
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);
        RecordingEmitter broken = new RecordingEmitter();
        broken.failSends();
        RecordingEmitter healthy = new RecordingEmitter();
        fanout.register(TURN_ID, broken, 0);
        fanout.register(TURN_ID, healthy, 0);

        assertThatCode(() -> fanout.publish(TURN_ID, envelope(1, "agent_event")))
                .doesNotThrowAnyException();

        assertThat(fanout.subscriberCount(TURN_ID)).isEqualTo(1);
        assertThat(healthy.sequences()).containsExactly(1);
    }

    @Test
    void subscriberOverTheLimitOnlyGetsHistoryAndIsClosed() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        when(store.eventsAfter(eq(TURN_ID), anyInt()))
                .thenReturn(List.of(envelope(1, "turn_started")));
        AgentTurnFanout fanout = fanout(store, 1, 64, Runnable::run);
        RecordingEmitter first = new RecordingEmitter();
        RecordingEmitter overflowed = new RecordingEmitter();
        fanout.register(TURN_ID, first, 0);

        fanout.register(TURN_ID, overflowed, 0);

        assertThat(fanout.subscriberCount(TURN_ID)).isEqualTo(1);
        assertThat(overflowed.sequences()).containsExactly(1);
        assertThat(overflowed.isCompleted()).isTrue();
    }

    @Test
    void slowSubscriberIsDroppedWhenBacklogExceedsQueueDepth() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        when(store.eventsAfter(eq(TURN_ID), anyInt())).thenReturn(List.of());
        // 只排队、不执行：模拟慢客户端，队列无法被排空。
        List<Runnable> deferred = new ArrayList<>();
        Consumer<Runnable> stalling = deferred::add;
        AgentTurnFanout fanout = fanout(store, 4, 1, stalling);
        RecordingEmitter slow = new RecordingEmitter();
        fanout.register(TURN_ID, slow, 0);

        fanout.publish(TURN_ID, envelope(1, "agent_event"));
        fanout.publish(TURN_ID, envelope(2, "agent_event"));

        assertThat(fanout.subscriberCount(TURN_ID)).isZero();
        assertThat(deferred.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void storeFailureDuringReplayDoesNotEscapeAndStillAllowsLiveDelivery() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        when(store.eventsAfter(eq(TURN_ID), anyInt()))
                .thenThrow(new IllegalStateException("db down"));
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);
        RecordingEmitter emitter = new RecordingEmitter();

        assertThatCode(() -> fanout.register(TURN_ID, emitter, 0)).doesNotThrowAnyException();
        assertThat(fanout.subscriberCount(TURN_ID)).isEqualTo(1);

        assertThatCode(() -> fanout.publish(TURN_ID, envelope(1, "agent_event")))
                .doesNotThrowAnyException();
        assertThat(emitter.sequences()).containsExactly(1);
    }

    @Test
    void publishWithoutSubscribersAndUnknownTurnAreNoOps() {
        AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        AgentTurnFanout fanout = fanout(store, 4, 64, Runnable::run);

        assertThatCode(() -> fanout.publish("nobody-listening", envelope(1, "agent_event")))
                .doesNotThrowAnyException();
        assertThatCode(() -> fanout.complete("nobody-listening")).doesNotThrowAnyException();
        assertThatCode(() -> fanout.unregister("nobody-listening", new RecordingEmitter()))
                .doesNotThrowAnyException();
        assertThat(fanout.subscriberCount("nobody-listening")).isZero();
    }

    @Test
    void frameKeepsPayloadShapeSharedWithPrimaryStream() {
        AgentTurnEventEnvelope envelope = new AgentTurnEventEnvelope(
                TURN_ID, 7, "agent_event", Map.of("eventType", "AGENT_TRACE"), "{\"eventType\":\"AGENT_TRACE\"}");

        assertThat(AgentTurnFanout.frameJson(envelope))
                .isEqualTo("{\"turnId\":\"turn-1\",\"seq\":7,\"type\":\"agent_event\","
                        + "\"payload\":{\"eventType\":\"AGENT_TRACE\"}}");
    }

    private static AgentTurnFanout fanout(AgentTurnEventStore store,
                                          int maxSubscribers,
                                          int maxQueueDepth,
                                          Consumer<Runnable> dispatcher) {
        return new AgentTurnFanout(store, maxSubscribers, maxQueueDepth, dispatcher);
    }

    private static AgentTurnEventEnvelope envelope(int seq, String type) {
        return new AgentTurnEventEnvelope(TURN_ID, seq, type, Map.of("seq", seq));
    }

    private static AgentTurnEntity turn(String status) {
        AgentTurnEntity entity = new AgentTurnEntity();
        entity.setId(TURN_ID);
        entity.setStatus(status);
        return entity;
    }

    /** 记录已发送帧的 SseEmitter：不依赖容器，直接截获 {@code send(SseEventBuilder)}。 */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> frames = new CopyOnWriteArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private volatile boolean failSends;

        private RecordingEmitter() {
            super(60_000L);
        }

        private void failSends() {
            this.failSends = true;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failSends) throw new IOException("client gone");
            Set<ResponseBodyEmitter.DataWithMediaType> data = builder.build();
            for (ResponseBodyEmitter.DataWithMediaType entry : data) {
                if (entry.getData() instanceof String text) frames.add(text);
            }
        }

        @Override
        public void complete() {
            completed.set(true);
        }

        private boolean isCompleted() {
            return completed.get();
        }

        /** 只取 data 帧（builder 还会带一条 "event:xxx" 行）。 */
        private List<String> jsonFrames() {
            return frames.stream().filter(frame -> frame.startsWith("{")).toList();
        }

        private List<Integer> sequences() {
            List<Integer> out = new ArrayList<>();
            for (String frame : jsonFrames()) {
                int at = frame.indexOf("\"seq\":");
                if (at < 0) continue;
                int start = at + 6;
                int end = start;
                while (end < frame.length() && Character.isDigit(frame.charAt(end))) end++;
                out.add(Integer.parseInt(frame.substring(start, end)));
            }
            return out;
        }

        private List<String> types() {
            List<String> out = new ArrayList<>();
            for (String frame : jsonFrames()) {
                int at = frame.indexOf("\"type\":\"");
                if (at < 0) continue;
                int start = at + 8;
                int end = frame.indexOf('"', start);
                out.add(frame.substring(start, end));
            }
            return out;
        }
    }
}