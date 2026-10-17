package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.moon.moonagent.ai.agent.agentscope.MoonAgentRuntime;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.ai.agent.recovery.CheckpointClaim;
import com.moon.moonagent.ai.agent.recovery.ResumeCommand;
import com.moon.moonagent.ai.agent.sandbox.SandboxArtifactPublisher;
import com.moon.moonagent.ai.agent.sandbox.SandboxOutputReconciler;
import com.moon.moonagent.ai.agent.turn.AgentScopeEventMapper;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventEnvelope;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventStore;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.AgentTurnEntity;
import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.dto.response.MessagePageVO;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatServiceImplTurnTest {

    @Test
    void createSideChatPersistsATemporaryConversationAndClonesStableContext() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentConversation source = conversation();
        source.setProjectId("project-1");
        source.setTitle("主对话");
        source.setApprovalMode("REQUEST_APPROVAL");

        when(conversationMapper.selectById("conversation-1")).thenReturn(source);
        when(messageMapper.selectCount(any())).thenReturn(2L);
        when(runtime.enabled()).thenReturn(true);
        when(runtime.cloneSessionState(eq("user-1"), eq("conversation-1"), anyString(), any()))
                .thenReturn(2);

        AgentChatServiceImpl service = service(
                conversationMapper,
                messageMapper,
                mock(AgentRuntimeSupport.class),
                mock(CheckpointResumeService.class),
                mock(ConversationLock.class),
                mock(PlatformTransactionManager.class));
        inject(service, "agentScopeMediaRuntime", runtime);

        AgentConversation side = service.createSideChat("user-1", "conversation-1");

        assertThat(side.getStatus()).isEqualTo("temporary");
        assertThat(side.getProjectId()).isEqualTo("project-1");
        assertThat(side.getApprovalMode()).isEqualTo("REQUEST_APPROVAL");
        assertThat(side.getAgentscopeSessionInitializedAt()).isNotNull();
        verify(conversationMapper).insert(side);
        verify(runtime).cloneSessionState(
                eq("user-1"), eq("conversation-1"), eq(side.getId()), any());
    }

    @Test
    void closeSideChatClearsRuntimeAndDeletesTheTemporaryConversation() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentConversation side = conversation();
        side.setStatus("temporary");

        when(conversationMapper.selectById("conversation-1")).thenReturn(side);
        when(lock.isLocked("conversation-1")).thenReturn(false);

        AgentChatServiceImpl service = service(
                conversationMapper,
                mock(AgentMessageMapper.class),
                mock(AgentRuntimeSupport.class),
                mock(CheckpointResumeService.class),
                lock,
                mock(PlatformTransactionManager.class));
        inject(service, "agentScopeMediaRuntime", runtime);

        service.closeSideChat("user-1", "conversation-1");

        verify(runtime).clearSession("user-1", "conversation-1");
        verify(lock).releaseAndRemove("conversation-1");
        verify(conversationMapper).deleteById("conversation-1");
    }

    @Test
    void interruptDelegatesToTheAgentScopeSessionInterrupt() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentChatServiceImpl service = service(
                conversationMapper,
                mock(AgentMessageMapper.class),
                mock(AgentRuntimeSupport.class),
                mock(CheckpointResumeService.class),
                mock(ConversationLock.class),
                mock(PlatformTransactionManager.class));
        inject(service, "agentScopeMediaRuntime", runtime);
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());

        service.interrupt("user-1", "conversation-1");

        verify(runtime).interruptTurn("user-1", "conversation-1");
    }

    @Test
    void hitlPauseKeepsFullAssistantContentOnThePersistedCard() {
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentChatServiceImpl service = service(
                mock(AgentConversationMapper.class),
                messageMapper,
                mock(AgentRuntimeSupport.class),
                mock(CheckpointResumeService.class),
                mock(ConversationLock.class),
                mock(PlatformTransactionManager.class));

        service.updateHitlMessageContent("message-1", "## 方案 A\n\n## 方案 B");

        ArgumentCaptor<AgentMessage> updated = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo("message-1");
        assertThat(updated.getValue().getContent()).isEqualTo("## 方案 A\n\n## 方案 B");
    }

    @Test
    void hitlPauseMergesReasoningTraceAndToolDraftsIntoThePersistedCard() {
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentMessage persisted = message("message-1", "assistant", "请确认");
        persisted.setMetadata(Map.of(
                "kind", "hitl",
                "hitl", Map.of("formToken", "token-1", "pending", true)));
        when(messageMapper.selectById("message-1")).thenReturn(persisted);
        AgentChatServiceImpl service = service(
                mock(AgentConversationMapper.class),
                messageMapper,
                mock(AgentRuntimeSupport.class),
                mock(CheckpointResumeService.class),
                mock(ConversationLock.class),
                mock(PlatformTransactionManager.class));
        com.moon.moonagent.ai.agent.AgentReasoningSnapshot reasoning =
                new com.moon.moonagent.ai.agent.AgentReasoningSnapshot(
                        "已完成推理。", "先确认用户意图。", 1200L, "agentscope");
        com.moon.moonagent.ai.agent.AgentTraceEvent traceEvent =
                new com.moon.moonagent.ai.agent.AgentTraceEvent(
                        "trace-1", "model_reasoning", "moon", "agentscope",
                        "node_completed", "模型推理", "推理完成", "", "",
                        java.time.OffsetDateTime.now(), 1200L);
        // 暂停轮不走 persistAssistant，所以工具明细必须在这里显式带上，
        // 否则"暂停过一次"的回合刷新后工具调用明细仍然消失。
        Map<String, Object> toolDrafts = Map.of("call-1", Map.of(
                "name", "generate_image",
                "text", "{\"ok\":true}",
                "input", "",
                "status", "node_completed"));

        service.mergeHitlMessageThinking(
                "message-1",
                new AgentTurnResult(
                        "请确认",
                        Map.of("metrics", Map.of("durationMs", 1200), "toolDrafts", toolDrafts),
                        List.of(traceEvent),
                        reasoning,
                        List.of(),
                        true,
                        "message-1"));

        ArgumentCaptor<AgentMessage> updated = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo("message-1");
        assertThat(updated.getValue().getMetadata().get("reasoning")).isEqualTo(reasoning);
        assertThat(updated.getValue().getMetadata().get("agentTrace")).isEqualTo(List.of(traceEvent));
        assertThat(updated.getValue().getMetadata().get("metrics"))
                .isEqualTo(Map.of("durationMs", 1200));
        assertThat(updated.getValue().getMetadata().get("toolDrafts")).isEqualTo(toolDrafts);
        @SuppressWarnings("unchecked")
        Map<String, Object> hitl = (Map<String, Object>) updated.getValue().getMetadata().get("hitl");
        assertThat(hitl.get("formToken")).isEqualTo("token-1");
        assertThat(hitl.get("pending")).isEqualTo(true);
    }

    @ParameterizedTest
    @CsvSource({
            "pending,false,false,true",
            "claimed,true,false,false",
            "claimed,false,true,false",
            "claimed,false,false,true"
    })
    void pendingCheckpointRecoveryHonorsClaimsAndSubmittedCards(
            String status,
            boolean activeClaim,
            boolean submitted,
            boolean visible
    ) throws Exception {
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        CheckpointResumeService checkpoints = mock(CheckpointResumeService.class);
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setId("cp-1");
        checkpoint.setConversationId("conversation-1");
        checkpoint.setStatus(status);
        checkpoint.setAction("agentscope-confirm");
        checkpoint.setFormToken("token-1");
        checkpoint.setExpirationTime(java.time.OffsetDateTime.now().plusMinutes(10));
        checkpoint.setPlan("""
                {
                  "_hitlMessageId": "message-1",
                  "_formContent": "请确认",
                  "_actions": [{"id": "approve", "title": "批准"}]
                }
                """);
        AgentMessage message = message("message-1", "assistant", "请确认");
        message.setMetadata(Map.of(
                "kind", "hitl",
                "hitl", Map.of(
                        "formToken", "token-1",
                        "pending", !submitted)));
        AgentTurnEntity waitingTurn = new AgentTurnEntity();
        waitingTurn.setId("turn-1");
        when(checkpointMapper.selectOne(any())).thenReturn(checkpoint);
        when(checkpoints.hasActiveClaim(checkpoint)).thenReturn(activeClaim);
        when(messageMapper.selectById("message-1")).thenReturn(message);
        when(turnStore.findByCheckpoint("token-1")).thenReturn(waitingTurn);

        AgentChatServiceImpl service = service(
                mock(AgentConversationMapper.class),
                messageMapper,
                mock(AgentRuntimeSupport.class),
                checkpoints,
                mock(ConversationLock.class),
                mock(PlatformTransactionManager.class));
        inject(service, "checkpointMapper", checkpointMapper);
        inject(service, "turnEventStore", turnStore);

        Map<String, Object> pending = service.getPendingCheckpoint("conversation-1");

        if (visible) {
            assertThat(pending).isNotNull();
            assertThat(pending.get("formToken")).isEqualTo("token-1");
            assertThat(pending.get("turnId")).isEqualTo("turn-1");
        } else {
            assertThat(pending).isNull();
        }
    }

    @Test
    void messagePageSignsSandboxAttachmentUrls() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentMessage persisted = message("assistant-1", "assistant", "已生成文档");
        persisted.setMetadata(Map.of(
                "sandboxAttachments", List.of(Map.of(
                        "assetId", "asset-1",
                        "url", "/api/files/files/doc.md",
                        "kind", "file"))));
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(messageMapper.selectList(any())).thenReturn(List.of(persisted));

        AgentChatServiceImpl service = service(
                conversationMapper,
                messageMapper,
                mock(AgentRuntimeSupport.class),
                mock(CheckpointResumeService.class),
                mock(ConversationLock.class),
                mock(PlatformTransactionManager.class),
                new FileUrlSigner("test-signing-secret", 900));

        MessagePageVO page = service.listMessagesPage("user-1", "conversation-1", null, 50);

        List<?> attachments = (List<?>) page.records().getFirst().metadata().get("sandboxAttachments");
        Map<?, ?> attachment = (Map<?, ?>) attachments.getFirst();
        assertThat(attachment.get("url").toString())
                .startsWith("/api/files/files/doc.md?token=");
    }

    @Test
    void editTurnUpdatesTargetDeletesLaterMessagesAndRunsAgentScope() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        AgentScopeEventMapper eventMapper = mock(AgentScopeEventMapper.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        CheckpointResumeService checkpoints = mock(CheckpointResumeService.class);

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lock.acquire("conversation-1")).thenReturn(lease);
        when(runtime.enabled()).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(false);
        AgentMessage target = message("user-2", "user", "原问题");
        target.setMetadata(Map.of("messageInput", Map.of(
                "picUrls", List.of("/api/files/images/ref.png"),
                "assetIds", List.of("asset-1"))));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("user-1", "user", "第一条"),
                message("assistant-1", "assistant", "第一条回复"),
                target,
                message("assistant-2", "assistant", "旧回复")).reversed());
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        when(turnStore.start(any(), eq("修改后的问题"), anyString())).thenReturn(turn);
        when(turnStore.appendBatch(anyString(), any())).thenAnswer(envelope("turn-1"));
        when(runtime.runTurn(any(), any(), any(), any())).thenReturn(
                new AgentTurnResult("新回复", Map.of(), List.of(), null,
                        List.of(), false, ""));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support, checkpoints, lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", eventMapper);

        service.streamAgentScopeTurn(
                "user-1", "conversation-1", "修改后的问题", List.of(),
                List.of(), null, "user-2", null, List.of(), List.of(),
                new SseEmitter(0L));

        verify(messageMapper, timeout(2_000)).updateById(any(AgentMessage.class));
        ArgumentCaptor<AgentMessage> updated = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo("user-2");
        assertThat(updated.getValue().getContent()).isEqualTo("修改后的问题");
        verify(messageMapper).deleteBatchIds(List.of("assistant-2"));
        verify(runtime).rewindLastTurn("user-1", "conversation-1", "原问题");
        verify(runtime, timeout(2_000)).runTurn(any(), any(), any(), any());
        verify(lease, timeout(2_000).atLeastOnce()).close();
    }

    @Test
    void newTurnPersistsUserMessageWithItsInputSnapshot() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lock.acquire("conversation-1")).thenReturn(lease);
        when(runtime.enabled()).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(false);
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        when(turnStore.start(any(), eq("带参考图的问题"), anyString())).thenReturn(turn);
        when(turnStore.appendBatch(anyString(), any())).thenAnswer(envelope("turn-1"));
        when(runtime.runTurn(any(), any(), any(), any())).thenReturn(
                new AgentTurnResult("回复", Map.of(), List.of(), null,
                        List.of(), false, ""));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support,
                mock(CheckpointResumeService.class), lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));

        service.streamAgentScopeTurn(
                "user-1", "conversation-1", "带参考图的问题",
                List.of("/api/files/images/ref.png"), List.of("asset-1"),
                null, null, null, List.of(), List.of(), new SseEmitter(0L));

        ArgumentCaptor<AgentMessage> inserted = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper, timeout(2_000).atLeastOnce()).insert(inserted.capture());
        AgentMessage userMessage = inserted.getAllValues().stream()
                .filter(message -> "user".equals(message.getRole()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> input =
                (Map<String, Object>) userMessage.getMetadata().get("messageInput");
        assertThat(input.get("picUrls")).isEqualTo(List.of("/api/files/images/ref.png"));
        assertThat(input.get("assetIds")).isEqualTo(List.of("asset-1"));
    }

    @Test
    void newTurnBindsUnboundUploadedAttachmentsToConversation() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentConversation conversation = conversation();
        conversation.setProjectId("project-1");

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lock.acquire("conversation-1")).thenReturn(lease);
        when(runtime.enabled()).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(false);
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        when(turnStore.start(any(), anyString(), anyString())).thenReturn(turn);
        when(turnStore.appendBatch(anyString(), any())).thenAnswer(envelope("turn-1"));
        when(runtime.runTurn(any(), any(), any(), any())).thenReturn(
                new AgentTurnResult("回复", Map.of(), List.of(), null,
                        List.of(), false, ""));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, assetMapper, support,
                mock(CheckpointResumeService.class), lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));

        service.streamAgentScopeTurn(
                "user-1", "conversation-1", "读取计划书", List.of(), List.of(),
                null, null, null, List.of(), List.of(),
                List.of(new AgentAttachmentRequest(
                        "/api/files/files/plan.docx", "项目计划书.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        123L, "file", "upload", "asset-doc", null)),
                List.of(),
                new SseEmitter(0L));

        verify(assetMapper, timeout(2_000)).bindConversation(
                List.of("asset-doc"), "conversation-1", "user-1", "project-1");
    }

    @Test
    void turnCreationFailureReturnsAnErrorEnvelopeWithoutPersistingUserContent()
            throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        SseEmitter emitter = mock(SseEmitter.class);

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(lock.acquire("conversation-1")).thenReturn(lease);
        when(runtime.enabled()).thenReturn(true);
        when(support.friendlyErrorText(any(), any())).thenReturn("运行时暂不可用");
        when(turnStore.start(any(), any(), anyString())).thenThrow(
                new IllegalStateException("agent_turns missing"));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support,
                mock(CheckpointResumeService.class), lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));

        service.streamAgentScopeTurn(
                "user-1", "conversation-1", "会失败的问题", List.of(),
                List.of(), null, null, null, List.of(), List.of(), emitter);

        verify(emitter, timeout(2_000).atLeastOnce())
                .send(any(SseEmitter.SseEventBuilder.class));
        verify(messageMapper, never()).insert(any(AgentMessage.class));
        verify(lease, timeout(2_000).atLeastOnce()).close();
    }

    @Test
    void resumeKeepsTheOriginalTurnInputForPreflight() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        AgentScopeEventMapper eventMapper = mock(AgentScopeEventMapper.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        CheckpointResumeService checkpoints = mock(CheckpointResumeService.class);
        ArgumentCaptor<com.moon.moonagent.ai.agent.handler.OrchestrationRequest> requestCaptor =
                ArgumentCaptor.forClass(com.moon.moonagent.ai.agent.handler.OrchestrationRequest.class);

        AgentTurnEntity originalTurn = new AgentTurnEntity();
        originalTurn.setId("turn-1");
        originalTurn.setConversationId("conversation-1");
        originalTurn.setStatus("waiting_user");
        originalTurn.setInputText("为80㎡两居室生成4张室内设计效果图");
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setAction("agentscope-confirm");
        checkpoint.setPlan("{\"items\":[{\"kind\":\"permission\"}]}");
        CheckpointClaim claim = new CheckpointClaim(
                checkpoint,
                ResumeCommand.of("approve", "", Map.of(), List.of(), "", null),
                "lease-1");

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lock.acquire("conversation-1")).thenReturn(lease);
        when(runtime.enabled()).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(false);
        when(turnStore.find("turn-1")).thenReturn(originalTurn);
        when(turnStore.resume("turn-1")).thenReturn(originalTurn);
        when(turnStore.appendBatch(anyString(), any())).thenAnswer(envelope("turn-1"));
        when(checkpoints.claim(eq("conversation-1"), eq("token-1"), any()))
                .thenReturn(claim);
        when(runtime.resumeTurn(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTurnResult("已完成", Map.of(), List.of(), null,
                        List.of(), false, ""));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support, checkpoints, lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", eventMapper);

        service.resumeAgentScopeTurn(
                "user-1", "conversation-1", "turn-1", "token-1", "approve",
                "", Map.of(), List.of(), null, mock(SseEmitter.class));

        verify(runtime, timeout(2_000)).resumeTurn(
                any(), any(), requestCaptor.capture(), any(), any(), any(), any());
        assertThat(requestCaptor.getValue().getContent())
                .isEqualTo("为80㎡两居室生成4张室内设计效果图");
    }

    @Test
    void resumeKeepsTraceSinkActiveWhileTheRuntimeRuns() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        CheckpointResumeService checkpoints = mock(CheckpointResumeService.class);
        AgentTurnEntity originalTurn = new AgentTurnEntity();
        originalTurn.setId("turn-1");
        originalTurn.setConversationId("conversation-1");
        originalTurn.setStatus("waiting_user");
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setAction("agentscope-confirm");
        checkpoint.setPlan("{\"items\":[{\"kind\":\"permission\"}]}");
        CheckpointClaim claim = new CheckpointClaim(
                checkpoint,
                ResumeCommand.of("approve", "", Map.of(), List.of(), "", null),
                "lease-1");

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lock.acquire("conversation-1")).thenReturn(lease);
        when(runtime.enabled()).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(false);
        when(turnStore.find("turn-1")).thenReturn(originalTurn);
        when(turnStore.resume("turn-1")).thenReturn(originalTurn);
        when(turnStore.appendBatch(anyString(), any())).thenAnswer(envelope("turn-1"));
        when(checkpoints.claim(eq("conversation-1"), eq("token-1"), any()))
                .thenReturn(claim);
        AtomicInteger sinkChecks = new AtomicInteger();
        List<com.moon.moonagent.ai.agent.AgentTraceEvent> traceSinkEvents =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        com.moon.moonagent.ai.agent.AgentTraceEvent traceEvent =
                new com.moon.moonagent.ai.agent.AgentTraceEvent(
                        "trace-1", "model_reasoning", "moon", "agentscope",
                        "node_started", "模型推理", "", "", "",
                        java.time.OffsetDateTime.parse("2026-10-10T08:00:00Z"), null);
        when(runtime.resumeTurn(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    com.moon.moonagent.ai.agent.handler.OrchestrationRequest request =
                            invocation.getArgument(2);
                    if (request.getTrace() != null && request.getTrace().hasEventSink()) {
                        sinkChecks.incrementAndGet();
                        // runtime 运行期间真实 sink 必须可用：推送一条 AGENT_TRACE，它必须经 publisher 落库
                        request.getTrace().setEventSink(traceSinkEvents::add);
                        request.getTrace().setEventSink(null);
                    }
                    return new AgentTurnResult("已完成", Map.of(), List.of(), null,
                            List.of(), false, "");
                });

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support, checkpoints, lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));

        service.resumeAgentScopeTurn(
                "user-1", "conversation-1", "turn-1", "token-1", "approve",
                "", Map.of(), List.of(), null, mock(SseEmitter.class));

        verify(runtime, timeout(2_000)).resumeTurn(
                any(), any(), any(), any(), any(), any(), any());
        assertThat(sinkChecks).hasValue(1);
        // turn_started 与 AGENT_TRACE 都必须由 publisher 走 appendBatch（不再有直接 append）
        verify(turnStore, timeout(2_000).times(2)).appendBatch(anyString(), any());
    }

    @Test
    void resumePublishesSandboxArtifactsToTheFinalAssistantMetadata() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        CheckpointResumeService checkpoints = mock(CheckpointResumeService.class);
        SandboxOutputReconciler reconciler = mock(SandboxOutputReconciler.class);
        AgentConversation conversation = conversation();
        AgentTurnEntity originalTurn = new AgentTurnEntity();
        originalTurn.setId("turn-1");
        originalTurn.setConversationId("conversation-1");
        originalTurn.setStatus("waiting_user");
        originalTurn.setInputText("生成一个 docx");
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setAction("agentscope-confirm");
        checkpoint.setPlan("{\"items\":[{\"kind\":\"permission\"}]}");
        CheckpointClaim claim = new CheckpointClaim(
                checkpoint,
                ResumeCommand.of("approve", "", Map.of(), List.of(), "", null),
                "lease-1");
        AgentAsset asset = new AgentAsset();
        asset.setId("asset-1");
        asset.setUrl("/api/files/files/report.docx");
        asset.setOriginalName("report.docx");
        asset.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lock.acquire("conversation-1")).thenReturn(lease);
        when(runtime.enabled()).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(false);
        when(turnStore.find("turn-1")).thenReturn(originalTurn);
        when(turnStore.resume("turn-1")).thenReturn(originalTurn);
        when(turnStore.appendBatch(anyString(), any())).thenAnswer(envelope("turn-1"));
        when(checkpoints.claim(eq("conversation-1"), eq("token-1"), any()))
                .thenReturn(claim);
        when(runtime.resumeTurn(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTurnResult("文档已生成", Map.of(), List.of(), null,
                        List.of(), false, ""));
        when(assetMapper.selectById("asset-1")).thenReturn(asset);
        when(reconciler.reconcileNew(conversation, "turn-1")).thenReturn(
                new SandboxArtifactPublisher.PublishedArtifacts(
                        List.of("asset-1"), List.of("/api/files/files/report.docx")));

        AgentChatServiceImpl service = service(
                conversationMapper,
                messageMapper,
                assetMapper,
                support,
                checkpoints,
                lock,
                transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));
        inject(service, "sandboxOutputReconciler", reconciler);

        service.resumeAgentScopeTurn(
                "user-1", "conversation-1", "turn-1", "token-1", "approve",
                "", Map.of(), List.of(), null, mock(SseEmitter.class));

        ArgumentCaptor<AgentMessage> saved = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper, timeout(2_000)).insert(saved.capture());
        List<?> attachments = (List<?>) saved.getValue().getMetadata().get("sandboxAttachments");
        Map<?, ?> attachment = (Map<?, ?>) attachments.getFirst();
        assertThat(attachment.get("assetId")).isEqualTo("asset-1");
        assertThat(attachment.get("url")).isEqualTo("/api/files/files/report.docx");
    }

    private static AgentChatServiceImpl service(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            AgentRuntimeSupport support,
            CheckpointResumeService checkpoints,
            ConversationLock lock,
            PlatformTransactionManager transactionManager) {
        return service(
                conversationMapper,
                messageMapper,
                support,
                checkpoints,
                lock,
                transactionManager,
                mock(FileUrlSigner.class));
    }

    private static AgentChatServiceImpl service(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            AgentRuntimeSupport support,
            CheckpointResumeService checkpoints,
            ConversationLock lock,
            PlatformTransactionManager transactionManager,
            FileUrlSigner fileUrlSigner) {
        return new AgentChatServiceImpl(
                conversationMapper,
                mock(AgentGenerationService.class),
                messageMapper,
                mock(AgentAssetMapper.class),
                mock(ConversationTitleService.class),
                mock(StoryboardClient.class),
                mock(FileStorageService.class),
                fileUrlSigner,
                mock(AiConfigProperties.class),
                support,
                mock(AgentCheckpointMapper.class),
                checkpoints,
                lock,
                mock(ProjectCreativeContextService.class),
                mock(com.moon.moonagent.ai.agent.agentscope.AgentCanvasSessionService.class),
                mock(com.moon.moonagent.ai.agent.agentscope.AgentCanvasRunService.class),
                mock(com.moon.moonagent.ai.agent.context.ConversationContextCleanupService.class),
                transactionManager);
    }

    private static AgentChatServiceImpl service(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            AgentAssetMapper assetMapper,
            AgentRuntimeSupport support,
            CheckpointResumeService checkpoints,
            ConversationLock lock,
            PlatformTransactionManager transactionManager) {
        return new AgentChatServiceImpl(
                conversationMapper,
                mock(AgentGenerationService.class),
                messageMapper,
                assetMapper,
                mock(ConversationTitleService.class),
                mock(StoryboardClient.class),
                mock(FileStorageService.class),
                mock(com.moon.moonagent.security.FileUrlSigner.class),
                mock(AiConfigProperties.class),
                support,
                mock(AgentCheckpointMapper.class),
                checkpoints,
                lock,
                mock(ProjectCreativeContextService.class),
                mock(com.moon.moonagent.ai.agent.agentscope.AgentCanvasSessionService.class),
                mock(com.moon.moonagent.ai.agent.agentscope.AgentCanvasRunService.class),
                mock(com.moon.moonagent.ai.agent.context.ConversationContextCleanupService.class),
                transactionManager);
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setStatus("active");
        conversation.setOrchestrationMode("AGENTSCOPE");
        conversation.setAgentscopeSessionInitializedAt(java.time.OffsetDateTime.now());
        return conversation;
    }

    private static AgentMessage message(String id, String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setId(id);
        message.setConversationId("conversation-1");
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    /**
     * 所有 turn 事件现在都经 publisher 走 {@code appendBatch}；这里模拟 store 只回一条已分配序号的信封。
     * 断言重点是事件是否走了唯一有序通道，不依赖具体序号。
     */
    private static org.mockito.stubbing.Answer<List<AgentTurnEventEnvelope>> envelope(String turnId) {
        return invocation -> List.of(new AgentTurnEventEnvelope(
                turnId, 1, "turn_started", Map.of("ok", true)));
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = AgentChatServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
