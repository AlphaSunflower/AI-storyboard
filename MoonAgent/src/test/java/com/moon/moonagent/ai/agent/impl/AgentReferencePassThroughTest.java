package com.moon.moonagent.ai.agent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.moon.moonagent.ai.agent.agentscope.MoonAgentRuntime;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.recovery.CheckpointClaim;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.ai.agent.recovery.ResumeCommand;
import com.moon.moonagent.ai.agent.turn.AgentScopeEventMapper;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventEnvelope;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventStore;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.request.AgentReferenceRequest;
import com.moon.moonagent.dto.request.AgentSendMessageRequest;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.AgentTurnEntity;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.service.FileStorageService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-a 聚焦测试：「本轮引用资料」{@code references} 与 {@code attachments} 并行、
 * 全链路透传——请求反序列化 / 校验边界 / {@link OrchestrationRequest#getReferences()} 往返 /
 * 新建轮与编辑重发落库 messageInput.references / HITL 续流从 messageInput 恢复。
 */
class AgentReferencePassThroughTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final String REQUEST_JSON = """
            {
              "content": "把 @分镜3 的镜头拉近一点，参考 @设定.docx 与 @对话《分镜重做》",
              "clientRequestId": "req-refs-1",
              "references": [
                {"kind":"scene","id":"scene-3","token":"@分镜3","name":"分镜3","sceneNumber":3},
                {"kind":"library_file","id":"lib-1","token":"@设定.docx","name":"设定.docx",
                 "mediaId":"media-1","url":"/api/library/media/media-1/content",
                 "mimeType":"application/msword","sizeBytes":123},
                {"kind":"local_file","id":"asset-1","token":"@脚本.docx","name":"脚本.docx",
                 "assetId":"asset-1","url":"/api/files/files/script.docx",
                 "mimeType":"application/msword","sizeBytes":456},
                {"kind":"conversation","id":"conv-2","token":"@对话《分镜重做》","name":"分镜重做"}
              ]
            }
            """;

    @Test
    void requestJsonWithReferencesDeserializesEveryKind() throws Exception {
        AgentSendMessageRequest request =
                new ObjectMapper().readValue(REQUEST_JSON, AgentSendMessageRequest.class);

        assertThat(request.content()).contains("@分镜3");
        assertThat(request.references()).hasSize(4);
        assertThat(request.references())
                .extracting(AgentReferenceRequest::kind)
                .containsExactly("scene", "library_file", "local_file", "conversation");
        assertThat(request.references().getFirst().sceneNumber()).isEqualTo(3);
        assertThat(request.references().get(1).mediaId()).isEqualTo("media-1");
        assertThat(request.references().get(2).assetId()).isEqualTo("asset-1");
        assertThat(request.references().get(2).sizeBytes()).isEqualTo(456L);
        assertThat(request.references().get(3).token()).isEqualTo("@对话《分镜重做》");
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void invalidKindIsRejectedByValidation() {
        AgentSendMessageRequest request = requestWith(List.of(reference("bogus", "id-1")));

        Set<ConstraintViolation<AgentSendMessageRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("引用类型不合法");
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .isEqualTo("references[0].kind");
    }

    @Test
    void blankKindIdAndTokenAreRejectedByValidation() {
        AgentReferenceRequest blank =
                new AgentReferenceRequest("scene", "  ", "", null, null, null, null, null, null, null);

        Set<ConstraintViolation<AgentSendMessageRequest>> violations =
                VALIDATOR.validate(requestWith(List.of(blank)));

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder("引用 ID 不能为空", "引用标记不能为空");
    }

    @Test
    void moreThanTenReferencesAreRejectedByValidation() {
        List<AgentReferenceRequest> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(reference("scene", "scene-" + i));
        }

        Set<ConstraintViolation<AgentSendMessageRequest>> violations =
                VALIDATOR.validate(requestWith(eleven));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("引用资料最多 10 项");
        assertThat(VALIDATOR.validate(requestWith(eleven.subList(0, 10)))).isEmpty();
    }

    @Test
    void orchestrationRequestKeepsReferencesOnSetAndGet() {
        OrchestrationRequest request = new OrchestrationRequest(
                conversation(), "问题", List.of(), new SseEmitter(0L));
        List<AgentReferenceRequest> references = List.of(
                reference("scene", "scene-3"),
                new AgentReferenceRequest("conversation", "conv-2", "@对话《分镜重做》", "分镜重做",
                        null, null, null, null, null, null));

        assertThat(request.getReferences()).isEmpty();
        request.setReferences(references);

        assertThat(request.getReferences()).containsExactlyElementsOf(references);
        assertThat(request.getReferences().get(1).token()).isEqualTo("@对话《分镜重做》");
        assertThat(request.getReferences().getFirst().sceneNumber()).isEqualTo(3);

        request.setReferences(null);
        assertThat(request.getReferences()).isEmpty();
    }

    @Test
    void newTurnPassesReferencesToRuntimeAndPersistsThemInMessageInput() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        ArgumentCaptor<OrchestrationRequest> requestCaptor =
                ArgumentCaptor.forClass(OrchestrationRequest.class);

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
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
                conversationMapper, messageMapper, support,
                mock(CheckpointResumeService.class), lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));

        List<AgentReferenceRequest> references = List.of(
                reference("scene", "scene-3"),
                new AgentReferenceRequest("conversation", "conv-2", "@对话《分镜重做》", "分镜重做",
                        null, null, null, null, null, null));

        service.streamAgentScopeTurn(
                "user-1", "conversation-1", "把 @分镜3 的镜头拉近一点", List.of(), List.of(),
                null, null, null, List.of(), List.of(),
                "req-refs-1", List.of(), references, null, new SseEmitter(0L));

        verify(runtime, timeout(2_000)).runTurn(any(), requestCaptor.capture(), any(), any());
        assertThat(requestCaptor.getValue().getReferences())
                .extracting(AgentReferenceRequest::token)
                .containsExactly("@分镜3", "@对话《分镜重做》");

        AgentMessage userMessage = capturedUserMessage(messageMapper);
        assertThat(messageInputOf(userMessage).get("references")).isEqualTo(references);
    }

    @Test
    void editedTurnRestoresReferencesFromThePreviousMessageInput() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        ArgumentCaptor<OrchestrationRequest> requestCaptor =
                ArgumentCaptor.forClass(OrchestrationRequest.class);

        AgentMessage target = message("user-2", "user", "原问题 @分镜3");
        Map<String, Object> previousInput = new LinkedHashMap<>();
        previousInput.put("picUrls", List.of("/api/files/images/ref.png"));
        previousInput.put("references", List.of(new LinkedHashMap<>(Map.of(
                "kind", "scene", "id", "scene-3", "token", "@分镜3",
                "name", "分镜3", "sceneNumber", 3))));
        target.setMetadata(Map.of("messageInput", previousInput));

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lock.acquire("conversation-1")).thenReturn(lease);
        when(runtime.enabled()).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(false);
        // listMessages() 会 reversed()，这里按既有测试口径给「新→旧」再反转成 created_at 正序
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("user-1", "user", "第一条"),
                message("assistant-1", "assistant", "第一条回复"),
                target,
                message("assistant-2", "assistant", "旧回复")).reversed());
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        when(turnStore.start(any(), anyString(), anyString())).thenReturn(turn);
        when(turnStore.appendBatch(anyString(), any())).thenAnswer(envelope("turn-1"));
        when(runtime.runTurn(any(), any(), any(), any())).thenReturn(
                new AgentTurnResult("新回复", Map.of(), List.of(), null,
                        List.of(), false, ""));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support,
                mock(CheckpointResumeService.class), lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));

        // 重发时客户端**没有这个字段**（null，例如老客户端/工具调用）→ 从 previousInput 恢复，不能丢引用。
        // 注意：显式 `List.of()` 表示「改后的正文不再引用任何东西」，是另一种语义，见下一个用例。
        service.streamAgentScopeTurn(
                "user-1", "conversation-1", "把 @分镜3 拉近一点", List.of(), List.of(),
                null, "user-2", null, List.of(), List.of(),
                List.of(), null, new SseEmitter(0L));

        verify(runtime, timeout(2_000)).runTurn(any(), requestCaptor.capture(), any(), any());
        assertThat(requestCaptor.getValue().getReferences())
                .extracting(AgentReferenceRequest::token)
                .containsExactly("@分镜3");

        ArgumentCaptor<AgentMessage> updated = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper, timeout(2_000)).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo("user-2");
        assertThat(updated.getValue().getContent()).isEqualTo("把 @分镜3 拉近一点");
        List<?> restored = (List<?>) messageInputOf(updated.getValue()).get("references");
        assertThat(restored).hasSize(1);
        assertThat(restored.getFirst()).isInstanceOf(AgentReferenceRequest.class);
        assertThat(((AgentReferenceRequest) restored.getFirst()).token()).isEqualTo("@分镜3");
        assertThat(((AgentReferenceRequest) restored.getFirst()).sceneNumber()).isEqualTo(3);
    }

    @Test
    void editedTurnTreatsExplicitEmptyReferencesAsAuthoritative() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        ArgumentCaptor<OrchestrationRequest> requestCaptor =
                ArgumentCaptor.forClass(OrchestrationRequest.class);

        AgentMessage target = message("user-2", "user", "原问题 @分镜3");
        Map<String, Object> previousInput = new LinkedHashMap<>();
        previousInput.put("references", List.of(new LinkedHashMap<>(Map.of(
                "kind", "scene", "id", "scene-3", "token", "@分镜3",
                "name", "分镜3", "sceneNumber", 3))));
        target.setMetadata(Map.of("messageInput", previousInput));

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lock.acquire("conversation-1")).thenReturn(lease);
        when(runtime.enabled()).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(false);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("user-1", "user", "第一条"),
                message("assistant-1", "assistant", "第一条回复"),
                target,
                message("assistant-2", "assistant", "旧回复")).reversed());
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        when(turnStore.start(any(), anyString(), anyString())).thenReturn(turn);
        when(turnStore.appendBatch(anyString(), any())).thenAnswer(envelope("turn-1"));
        when(runtime.runTurn(any(), any(), any(), any())).thenReturn(
                new AgentTurnResult("新回复", Map.of(), List.of(), null,
                        List.of(), false, ""));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support,
                mock(CheckpointResumeService.class), lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));

        // 用户把 `@分镜3` 从正文里删掉后按「重发」：前端显式传空数组，
        // 这是权威值——必须尊重，不能从 previousInput 恢复，否则删掉的引用会复活。
        service.streamAgentScopeTurn(
                "user-1", "conversation-1", "什么都不引用了", List.of(), List.of(),
                null, "user-2", null, List.of(), List.of(),
                List.of(), List.of(), new SseEmitter(0L));

        verify(runtime, timeout(2_000)).runTurn(any(), requestCaptor.capture(), any(), any());
        assertThat(requestCaptor.getValue().getReferences()).isEmpty();
    }

    @Test
    void hitlResumeRestoresReferencesFromThePersistedUserMessage() throws Exception {        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        ConversationLock.LockLease lease = mock(ConversationLock.LockLease.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        CheckpointResumeService checkpoints = mock(CheckpointResumeService.class);
        ArgumentCaptor<OrchestrationRequest> requestCaptor =
                ArgumentCaptor.forClass(OrchestrationRequest.class);

        AgentMessage userMessage = message("user-1", "user", "为 @分镜3 生成效果图");
        userMessage.setMetadata(Map.of("messageInput", Map.of("references", List.of(
                new LinkedHashMap<>(Map.of(
                        "kind", "scene", "id", "scene-3", "token", "@分镜3",
                        "name", "分镜3", "sceneNumber", 3))))));

        AgentTurnEntity originalTurn = new AgentTurnEntity();
        originalTurn.setId("turn-1");
        originalTurn.setConversationId("conversation-1");
        originalTurn.setStatus("waiting_user");
        originalTurn.setInputText("为 @分镜3 生成效果图");
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
        when(checkpoints.claim(eq("conversation-1"), eq("token-1"), any())).thenReturn(claim);
        when(messageMapper.selectList(any())).thenReturn(List.of(userMessage).reversed());
        when(runtime.resumeTurn(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new AgentTurnResult("已完成", Map.of(), List.of(), null,
                        List.of(), false, ""));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support, checkpoints, lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));

        service.resumeAgentScopeTurn(
                "user-1", "conversation-1", "turn-1", "token-1", "approve",
                "", Map.of(), List.of(), null, mock(SseEmitter.class));

        verify(runtime, timeout(2_000)).resumeTurn(
                any(), any(), requestCaptor.capture(), any(), any(), any(), any());
        assertThat(requestCaptor.getValue().getReferences())
                .extracting(AgentReferenceRequest::token)
                .containsExactly("@分镜3");
    }

    // ---------- helpers ----------

    private static AgentReferenceRequest reference(String kind, String id) {
        return new AgentReferenceRequest(kind, id, "@分镜3", "分镜3",
                null, null, null, null, null, 3);
    }

    private static AgentSendMessageRequest requestWith(List<AgentReferenceRequest> references) {
        return new AgentSendMessageRequest(
                "把 @分镜3 的镜头拉近一点", "req-1", null, null, null, null, null,
                references, List.of(), List.of(), List.of(), null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> messageInputOf(AgentMessage message) {
        return (Map<String, Object>) message.getMetadata().get("messageInput");
    }

    private static AgentMessage capturedUserMessage(AgentMessageMapper messageMapper) {
        ArgumentCaptor<AgentMessage> inserted = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper, timeout(2_000).atLeastOnce()).insert(inserted.capture());
        return inserted.getAllValues().stream()
                .filter(message -> "user".equals(message.getRole()))
                .findFirst()
                .orElseThrow();
    }

    private static AgentChatServiceImpl service(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            AgentRuntimeSupport support,
            CheckpointResumeService checkpoints,
            ConversationLock lock,
            PlatformTransactionManager transactionManager) {
        return new AgentChatServiceImpl(
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
                checkpoints,
                lock,
                mock(ProjectCreativeContextService.class),
                mock(com.moon.moonagent.ai.agent.agentscope.AgentCanvasSessionService.class),
                mock(com.moon.moonagent.ai.agent.agentscope.AgentCanvasRunService.class),
                mock(com.moon.moonagent.ai.agent.context.ConversationContextCleanupService.class),
                new com.moon.moonagent.ai.agent.turn.TurnSteerRegistry(),
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
