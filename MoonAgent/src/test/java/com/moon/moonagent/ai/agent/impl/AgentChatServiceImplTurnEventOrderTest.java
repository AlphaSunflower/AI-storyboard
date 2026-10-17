package com.moon.moonagent.ai.agent.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentInterruptException;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.moon.moonagent.ai.agent.agentscope.MoonAgentRuntime;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.ai.agent.sandbox.SandboxArtifactPublisher;
import com.moon.moonagent.ai.agent.sandbox.SandboxOutputReconciler;
import com.moon.moonagent.ai.agent.turn.AgentScopeEventMapper;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventEnvelope;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventStore;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventStoreTestSupport;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentTurnEntity;
import com.moon.moonagent.entity.AgentTurnEventEntity;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.service.FileStorageService;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * turn 控制流事件回归测试（红线 1）：`turn_started` → … → `turn_completed` / `turn_failed`
 * 必须全部经 {@link com.moon.moonagent.ai.agent.turn.AgentTurnEventPublisher} 写入 + 推送，
 * 表现为每个 turn 的 SSE 帧 seq 从 1 连续递增、写入顺序与投递顺序一致，且终态不越过其依赖的 delta。
 *
 * <p>装置：真 AgentChatServiceImpl + 假 store（按 appendBatch 的调用顺序分配 seq，模拟
 * `agent_turns.last_sequence` 的唯一序号）+ 记录型 SseEmitter。任何绕开 publisher 的直接
 * `store.append(...)` 都会立刻在连续性或投递顺序断言上暴露。</p>
 */
class AgentChatServiceImplTurnEventOrderTest {

    private static final String TURN_ID = "turn-1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void turnStartedToTurnCompletedYieldsContiguousSequenceWithDeltasFirst() {
        Harness harness = new Harness();
        harness.runWith(observer -> {
            observer.accept(new TextBlockDeltaEvent(null, "reply-1", "block-1", "你", null));
            observer.accept(new TextBlockDeltaEvent(null, "reply-1", "block-1", "好", null));
            return new AgentTurnResult("你好", Map.of(), List.of(), null, List.of(), false, "");
        });

        List<AgentTurnEventEnvelope> persisted = harness.awaitPersisted(4);
        List<RecordedFrame> frames = harness.frames();

        assertThat(persisted).extracting(AgentTurnEventEnvelope::seq).containsExactly(1, 2, 3, 4);
        assertThat(persisted).extracting(AgentTurnEventEnvelope::type).containsExactly(
                "turn_started", "TEXT_BLOCK_DELTA", "TEXT_BLOCK_DELTA", "turn_completed");
        // 写入顺序 == 投递顺序，没有第二次发射
        assertThat(frames).extracting(RecordedFrame::seq).containsExactly(1, 2, 3, 4);
        assertThat(frames).extracting(RecordedFrame::type).containsExactly(
                "turn_started", "TEXT_BLOCK_DELTA", "TEXT_BLOCK_DELTA", "turn_completed");
        assertThat(frames).allSatisfy(frame -> assertThat(frame.turnId()).isEqualTo(TURN_ID));
        // 线协议不回归：SSE data 必须仍是 {turnId,seq,type,payload} 四字段信封（前端 AgentTurnEventSequencer 依赖）
        assertThat(frames).allSatisfy(frame ->
                assertThat(topLevelFields(frame.json()))
                        .containsExactlyInAnyOrder("turnId", "seq", "type", "payload"));
    }

    @Test
    void turnFailedPathAlsoYieldsContiguousSequence() {
        Harness harness = new Harness();
        harness.runWith(observer -> {
            observer.accept(new TextBlockDeltaEvent(null, "reply-1", "block-1", "部分", null));
            throw new IllegalStateException("runtime exploded");
        });

        List<AgentTurnEventEnvelope> persisted = harness.awaitPersisted(3);
        List<RecordedFrame> frames = harness.frames();

        assertThat(persisted).extracting(AgentTurnEventEnvelope::seq).containsExactly(1, 2, 3);
        assertThat(frames).extracting(RecordedFrame::seq).containsExactly(1, 2, 3);
        assertThat(frames).extracting(RecordedFrame::type).containsExactly(
                "turn_started", "TEXT_BLOCK_DELTA", "turn_failed");
        assertThat(persisted.getLast().payload()).containsEntry("code", "50001");
        assertThat(frames.getLast().payload().path("code").asText()).isEqualTo("50001");
    }

    /**
     * 红线补充：用户打断（「停止生成」/「打断并发送」）不是"把已说出的字丢掉"。
     *
     * <p>打断收口必须同时做到三件事：turn 仍按同一有序 publisher 收尾（序号连续、终态不越过 delta）、
     * 已流出的正文连同「…」落库（刷新后仍在、后续回合上下文读得到）、终态帧带 {@code interrupted} 标记。</p>
     */
    @Test
    void interruptedTurnKeepsPartialReplyAndEmitsContiguousTerminalSequence() {
        Harness harness = new Harness();
        harness.runWith(observer -> {
            // 3 参构造器才是 (replyId, blockId, delta)；5 参是 (id, createdAt, replyId, blockId, delta)
            observer.accept(new TextBlockDeltaEvent("reply-1", "block-1", "我先说一半"));
            observer.accept(new TextBlockDeltaEvent("reply-1", "block-1", "就被叫停了"));
            throw new AgentInterruptException();
        });

        List<AgentTurnEventEnvelope> persisted = harness.awaitPersisted(4);
        List<RecordedFrame> frames = harness.frames();

        assertThat(persisted).extracting(AgentTurnEventEnvelope::seq).containsExactly(1, 2, 3, 4);
        assertThat(persisted).extracting(AgentTurnEventEnvelope::type).containsExactly(
                "turn_started", "TEXT_BLOCK_DELTA", "TEXT_BLOCK_DELTA", "turn_failed");
        assertThat(frames).extracting(RecordedFrame::seq).containsExactly(1, 2, 3, 4);
        assertThat(persisted.getLast().payload())
                .containsEntry("code", "40904")
                .containsEntry("interrupted", true)
                .containsEntry("content", "我先说一半就被叫停了…");
        // 打断收口必须把这段被截断的回复落库（否则刷新后消失、上下文也读不到）
        assertThat(harness.persistedAssistantContents()).containsExactly("我先说一半就被叫停了…");
    }

    /** 省略号只补一次：正文已以「…」结尾时不再叠加。 */
    @Test
    void interruptedReplyContentAppendsEllipsisOnce() {
        assertThat(AgentChatServiceImpl.interruptedReplyContent("说到一半"))
                .isEqualTo("说到一半…");
        assertThat(AgentChatServiceImpl.interruptedReplyContent("说到一半…"))
                .isEqualTo("说到一半…");
        assertThat(AgentChatServiceImpl.interruptedReplyContent("   "))
                .isEmpty();
        assertThat(AgentChatServiceImpl.interruptedReplyContent(null)).isEmpty();
    }

    /**
     * 「停止并发送」的时序红线：打断标志必须在拿到会话锁之后才清除。
     *
     * <p>若在请求线程就 clear，旧回合还没观察到打断信号就被清掉 → 一直跑到自然结束（用户视角
     * 「停止没反应」），同时新回合因会话锁仍被占用被 40901 拒绝。</p>
     */
    @Test
    void interruptFlagIsClearedOnlyAfterConversationLockIsHeld() {
        Harness harness = new Harness();
        harness.runWith(observer -> new AgentTurnResult("好了", Map.of(), List.of(), null, List.of(), false, ""));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(harness.lock, harness.support);
        order.verify(harness.lock).acquire("conversation-1");
        order.verify(harness.support).clearInterrupt("conversation-1");
    }

    @Test
    void liveFramesAndAfterSeqCompensationAgreeEventByEvent() {
        Harness harness = new Harness();
        harness.runWith(observer -> {
            observer.accept(new TextBlockDeltaEvent(null, "reply-1", "block-1", "实", null));
            observer.accept(new TextBlockDeltaEvent(null, "reply-1", "block-1", "时", null));
            return new AgentTurnResult("实时", Map.of(), List.of(), null, List.of(), false, "");
        });

        harness.awaitPersisted(4);
        List<RecordedFrame> live = harness.frames();
        List<AgentTurnEventEnvelope> compensated = harness.store.eventsAfter(TURN_ID, 0);

        assertThat(live).hasSize(4);
        assertThat(compensated).hasSameSizeAs(live);
        for (int index = 0; index < live.size(); index++) {
            assertThat(compensated.get(index).seq()).isEqualTo(live.get(index).seq());
            assertThat(compensated.get(index).type())
                    .isEqualTo(live.get(index).type());
            // 补偿读回的 payload 必须与直播帧里内联的 payload 完全一致（帧内 payload 就是 payloadJson 原文）
            assertThat(json(compensated.get(index).payloadJson()))
                    .isEqualTo(live.get(index).payload());
            assertThat(compensated.get(index).payloadJson())
                    .isEqualTo(live.get(index).payload().toString());
        }
    }

    private static JsonNode json(String text) {
        try {
            return OBJECT_MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException("测试解析 JSON 失败: " + text, e);
        }
    }

    /** 帧 JSON 的顶层字段名；用于钉住 {turnId,seq,type,payload} 信封形状。 */
    private static java.util.Set<String> topLevelFields(String frameJson) {
        java.util.Set<String> fields = new java.util.LinkedHashSet<>();
        json(frameJson).fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    /** runtime 的返回值/异常由调用方决定；observer 由测试推进。 */
    private interface TurnScript {
        AgentTurnResult execute(Consumer<AgentEvent> observer);
    }

    /** 一条真实投递到 SseEmitter 的帧（后端契约：data = {turnId,seq,type,payload}）。 */
    private record RecordedFrame(String type, String turnId, int seq, JsonNode payload, String json) {
    }

    /** 记录 turn 事件帧的 SseEmitter；只放行事件名+JSON 数据，忽略注释/心跳。 */
    private static final class RecordingEmitter extends SseEmitter {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final List<RecordedFrame> frames = Collections.synchronizedList(new ArrayList<>());

        private RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                Object data = item.getData();
                if (!(data instanceof String json) || !json.startsWith("{")) continue;
                JsonNode node = JSON.readTree(json);
                frames.add(new RecordedFrame(
                        node.path("type").asText(),
                        node.path("turnId").asText(),
                        node.path("seq").asInt(),
                        node.path("payload"),
                        json));
            }
        }

        @Override
        public void send(Object object) throws IOException {
            if (object instanceof SseEventBuilder builder) {
                send(builder);
                return;
            }
            super.send(object);
        }

        private List<RecordedFrame> frames() {
            synchronized (frames) {
                return List.copyOf(frames);
            }
        }
    }

    /** 与生产链路一致的最小装置：真 AgentChatServiceImpl + 假 store/runtime。 */
    private static final class Harness {
        private final Map<Integer, AgentTurnEventEntity> persisted =
                new ConcurrentSkipListMap<>();
        private final AtomicInteger lastSequence = new AtomicInteger();
        private final AgentTurnEventStore store = mock(AgentTurnEventStore.class);
        private final MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        private final SandboxOutputReconciler reconciler = mock(SandboxOutputReconciler.class);
        private final RecordingEmitter emitter = new RecordingEmitter();
        private final ConversationLock lock = mock(ConversationLock.class);
        private final AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        private final AgentChatServiceImpl service;

        private Harness() {
            AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
            ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
            PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
            AgentConversation conversation = new AgentConversation();
            conversation.setId("conversation-1");
            conversation.setUserId("user-1");
            conversation.setStatus("active");
            conversation.setOrchestrationMode("AGENTSCOPE");
            conversation.setAgentscopeSessionInitializedAt(OffsetDateTime.now());

            when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
            when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(lock.acquire("conversation-1")).thenReturn(lease);
            when(runtime.enabled()).thenReturn(true);
            when(support.isInterrupted("conversation-1")).thenReturn(false);
            // friendlyErrorText 是真实组件，永远返回非空文案；Map.of 不接受 null value，必须给出替身值
            when(support.friendlyErrorText(any(), any())).thenReturn("运行时错误");
            // 沙箱产物对账：默认没有新产物，返回原 result（reconcileNew 返回 null 会触发 NPE）
            when(reconciler.reconcileNew(any(), any())).thenReturn(
                    new SandboxArtifactPublisher.PublishedArtifacts(List.of(), List.of()));
            when(store.start(any(), anyString(), anyString())).thenAnswer(invocation -> {
                AgentTurnEntity turn = new AgentTurnEntity();
                turn.setId(TURN_ID);
                turn.setConversationId("conversation-1");
                turn.setRuntimeEngine("agentscope-java-2.0.4");
                return turn;
            });
            when(store.appendBatch(eq(TURN_ID), any())).thenAnswer(invocation -> {
                List<AgentTurnEventStore.EventDraft> drafts = invocation.getArgument(1);
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
                            AgentTurnEventStoreTestSupport.payloadMap(draft.payload()),
                            payloadJson));
                }
                return List.copyOf(envelopes);
            });
            when(store.eventsAfter(eq(TURN_ID), any(Integer.class))).thenAnswer(invocation -> {
                int afterSequence = invocation.getArgument(1);
                List<AgentTurnEventEnvelope> replay = new ArrayList<>();
                for (AgentTurnEventEntity entity : persisted.values()) {
                    if (entity.getSequence() <= afterSequence) continue;
                    replay.add(new AgentTurnEventEnvelope(
                            TURN_ID, entity.getSequence(), entity.getEventType(),
                            AgentTurnEventStoreTestSupport.fromJson(entity.getPayload()),
                            entity.getPayload()));
                }
                return List.copyOf(replay);
            });

            service = new AgentChatServiceImpl(
                    conversationMapper,
                    mock(AgentGenerationService.class),
                    messageMapper,
                    mock(AgentAssetMapper.class),
                    mock(ConversationTitleService.class),
                    mock(StoryboardClient.class),
                    mock(FileStorageService.class),
                    mock(FileUrlSigner.class),
                    mock(AiConfigProperties.class),
                    support,
                    mock(AgentCheckpointMapper.class),
                    mock(CheckpointResumeService.class),
                    lock,
                    mock(ProjectCreativeContextService.class),
                    mock(com.moon.moonagent.ai.agent.agentscope.AgentCanvasSessionService.class),
                    mock(com.moon.moonagent.ai.agent.agentscope.AgentCanvasRunService.class),
                    mock(com.moon.moonagent.ai.agent.context.ConversationContextCleanupService.class),
                    transactionManager);
            inject("agentScopeMediaRuntime", runtime);
            inject("turnEventStore", store);
            inject("agentScopeEventMapper", new AgentScopeEventMapper());
            inject("sandboxOutputReconciler", reconciler);
        }

        /** 记录 runtime 收到的事件推送回调并交给脚本驱动，然后按生产入口跑一轮。 */
        private void runWith(TurnScript script) {
            @SuppressWarnings("unchecked")
            Consumer<AgentEvent>[] holder = new Consumer[1];
            when(runtime.runTurn(any(), any(), any(), any())).thenAnswer(invocation -> {
                holder[0] = invocation.getArgument(3);
                return script.execute(holder[0]);
            });

            service.streamAgentScopeTurn(
                    "user-1", "conversation-1", "问题", List.of(), List.of(),
                    null, null, null, List.of(), List.of(), emitter);

            verify(runtime, timeout(2_000)).runTurn(any(), any(), any(), any());
        }

        private List<AgentTurnEventEnvelope> awaitPersisted(int expectedCount) {
            return await(() -> store.eventsAfter(TURN_ID, 0), expectedCount);
        }

        /** 本轮落库的 assistant 消息正文（打断收口必须留下被截断的回复）。 */
        private List<String> persistedAssistantContents() {
            List<String> contents = new ArrayList<>();
            for (org.mockito.invocation.Invocation invocation
                    : org.mockito.Mockito.mockingDetails(messageMapper).getInvocations()) {
                if (!"insert".equals(invocation.getMethod().getName())) continue;
                Object argument = invocation.getArgument(0);
                if (argument instanceof com.moon.moonagent.entity.AgentMessage message
                        && "assistant".equals(message.getRole())) {
                    contents.add(message.getContent());
                }
            }
            return contents;
        }

        private List<RecordedFrame> frames() {
            return emitter.frames();
        }

        private <T> List<T> await(Supplier<List<T>> reader, int expectedCount) {
            long deadline = System.currentTimeMillis() + 3_000L;
            List<T> current = reader.get();
            while (current.size() < expectedCount && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                current = reader.get();
            }
            return current;
        }

        private void inject(String fieldName, Object value) {
            try {
                Field field = AgentChatServiceImpl.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(service, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("测试注入失败: " + fieldName, e);
            }
        }
    }
}
