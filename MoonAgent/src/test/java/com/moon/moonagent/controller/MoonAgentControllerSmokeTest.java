package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.agentscope.AgentScopeAsyncTaskService;
import com.moon.moonagent.ai.agent.agentscope.AgentCanvasSessionService;
import com.moon.moonagent.ai.agent.task.AgentTaskEventHub;
import com.moon.moonagent.ai.agent.GenerationRecordService;
import com.moon.moonagent.ai.agent.PromptOptimizeService;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.config.SecurityConfig;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.service.StoryboardImageQueueService;
import com.moon.moonagent.service.StoryboardQueueRequestGuard;
import com.moon.moonagent.service.StoryboardVideoQueueService;
import com.moon.moonagent.service.ProjectOutputMaterialService;
import com.moon.moonagent.service.ConversationAssetGroupService;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
    AgentConversationController.class,
    AgentTaskController.class,
    StoryboardImageQueueController.class,
    StoryboardVideoQueueController.class
})
@Import(SecurityConfig.class)
@EnableConfigurationProperties(AgentAiConfigProperties.class)
@TestPropertySource(properties = {
        "ai.agent.agentscope.enabled=true",
        "storyboard.internal-secret="
})
class MoonAgentControllerSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentChatService chatService;
    @MockitoBean
    private AgentCanvasSessionService canvasSessionService;
    @MockitoBean
    private PromptOptimizeService optimizeService;
    @MockitoBean
    private GenerationRecordService generationRecordService;
    @MockitoBean
    private ProjectOutputMaterialService projectOutputMaterialService;
    @MockitoBean
    private ConversationAssetGroupService conversationAssetGroupService;
    @MockitoBean
    private AssetClient assetClient;
    @MockitoBean
    private StoryboardImageQueueService imageQueueService;
    @MockitoBean
    private StoryboardVideoQueueService videoQueueService;
    @MockitoBean
    private StoryboardQueueRequestGuard requestGuard;
    @MockitoBean
    private StoryboardClient storyboardClient;
    @MockitoBean
    private AgentScopeAsyncTaskService asyncTaskService;
    @MockitoBean
    private AgentTaskEventHub taskEventHub;

    private AgentConversation conversation() {
        AgentConversation c = new AgentConversation();
        c.setId("c1");
        c.setUserId("u1");
        c.setProjectId("p1");
        c.setTitle("会话");
        c.setStatus("active");
        return c;
    }

    private AgentMessage message() {
        AgentMessage m = new AgentMessage();
        m.setId("m1");
        m.setConversationId("c1");
        m.setRole("assistant");
        m.setContent("你好");
        return m;
    }

    private AgentAsset asset() {
        AgentAsset a = new AgentAsset();
        a.setId("a1");
        return a;
    }

    @Test
    void securedEndpoints_requireGatewayHeader() throws Exception {
        mockMvc.perform(get("/api/agent/conversations?projectId=p1"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/agent/storyboards/image/queue?projectId=p1"))
            .andExpect(status().isForbidden());
    }

    @Test
    void conversationEndpoints_returnContract() throws Exception {
        when(chatService.createConversation(
                anyString(), anyString(), anyString(), any(), any())).thenReturn(conversation());
        when(chatService.listConversations(anyString(), anyString())).thenReturn(List.of(conversation()));
        when(chatService.getOwnedConversation(anyString(), anyString())).thenReturn(conversation());
        when(chatService.getPendingCheckpoint(anyString())).thenReturn(Map.of("token", "t"));
        when(chatService.listMessages(anyString())).thenReturn(List.of(message()));
        when(chatService.listAssets(anyString(), anyInt(), anyInt()))
            .thenReturn(Map.of("records", List.of(), "total", 0));
        when(chatService.uploadReferenceImage(anyString(), any(), any(), any(), any()))
            .thenReturn("/api/files/images/x.png");
        when(chatService.confirmImageDone(anyString(), anyString())).thenReturn(true);
        when(chatService.interrupt(anyString(), anyString())).thenReturn(true);
        when(chatService.getVideoTaskStatus(anyString(), anyString())).thenReturn(Map.of("status", "processing"));
        when(asyncTaskService.getStatus("u1", "task-1")).thenReturn(Map.of(
                "taskId", "task-1", "status", "running",
                "title", "剧情诊断", "stage", "plot_diagnosis"));
        when(chatService.updateConversation(anyString(), anyString(), any(), any())).thenReturn(conversation());
        when(optimizeService.optimize(anyString())).thenReturn("优化后的提示词");
        when(generationRecordService.listRecords(anyString(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(Map.of("records", List.of(), "total", 0));
        when(generationRecordService.getRecord(anyString(), anyString())).thenReturn(asset());
        when(generationRecordService.refetch(anyString(), anyString())).thenReturn(asset());
        when(generationRecordService.retry(anyString(), anyString())).thenReturn(Map.of("ok", "true"));
        when(projectOutputMaterialService.list(anyString(), anyString(), any())).thenReturn(List.of());

        mockMvc.perform(post("/api/agent/conversations").header("X-User-Id", "u1")
                .contentType("application/json").content("{\"projectId\":\"p1\",\"title\":\"会话\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("c1"));
        mockMvc.perform(get("/api/agent/conversations?projectId=p1").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("c1"));
        mockMvc.perform(get("/api/agent/conversations/c1").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.conversation.id").value("c1"));
        mockMvc.perform(get("/api/agent/conversations/c1/pending-checkpoint").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").value("t"));
        mockMvc.perform(delete("/api/agent/conversations/c1").header("X-User-Id", "u1"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/agent/conversations/c1/messages").header("X-User-Id", "u1"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/agent/conversations/c1/messages").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].role").value("assistant"));
        mockMvc.perform(post("/api/agent/conversations/c1/messages").header("X-User-Id", "u1")
                .contentType("application/json").content("{\"content\":\"你好\"}"))
            .andExpect(status().isGone());
        mockMvc.perform(get("/api/agent/conversations/c1/assets?page=1&size=20").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(multipart("/api/agent/upload")
                .file(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}))
                .header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.url").value("/api/files/images/x.png"));
        mockMvc.perform(post("/api/agent/prompt/optimize").header("X-User-Id", "u1")
                .contentType("application/json").content("{\"content\":\"一段足够长的内容\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.optimized").value("优化后的提示词"));
        mockMvc.perform(post("/api/agent/prompt/optimize").header("X-User-Id", "u1")
                .contentType("application/json").content("{\"content\":\"短\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/agent/conversations/c1/confirm-done").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(post("/api/agent/conversations/c1/interrupt").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/api/agent/tasks/task-1").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("running"));
        mockMvc.perform(get("/api/agent/generation-records?page=1&size=20").header("X-User-Id", "u1"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/agent/projects/p1/output-materials?currentConversationId=c1").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/api/agent/generation-records/g1").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("a1"));
        mockMvc.perform(post("/api/agent/generation-records/g1/refetch").header("X-User-Id", "u1"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/agent/generation-records/g1/retry").header("X-User-Id", "u1"))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/agent/conversations/c1").header("X-User-Id", "u1")
                .contentType("application/json").content("{\"title\":\"改名\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("会话"));
        mockMvc.perform(delete("/api/agent/assets/a1").header("X-User-Id", "u1"))
            .andExpect(status().isOk());
    }

    @Test
    void generationRecords_endpoints_returnContract() throws Exception {
        when(generationRecordService.listRecords(anyString(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(Map.of("records", List.of(), "total", 0));
        when(generationRecordService.getRecord(anyString(), anyString())).thenReturn(asset());
        when(generationRecordService.refetch(anyString(), anyString())).thenReturn(asset());
        when(generationRecordService.retry(anyString(), anyString())).thenReturn(Map.of("ok", "true"));
        mockMvc.perform(get("/api/agent/generation-records?genType=video&page=1&size=20").header("X-User-Id", "u1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void imageAndVideoQueueEndpoints_returnContract() throws Exception {
        when(storyboardClient.getProject(anyString())).thenReturn(Map.of("userId", "u1"));
        when(imageQueueService.enqueue(any())).thenReturn(List.of());
        when(imageQueueService.listByProject(anyString(), any())).thenReturn(List.of());
        when(imageQueueService.getProjectId(anyString())).thenReturn("p1");
        when(videoQueueService.enqueue(any())).thenReturn(List.of());
        when(videoQueueService.listByProject(anyString(), any())).thenReturn(List.of());
        when(videoQueueService.getProjectId(anyString())).thenReturn("p1");

        mockMvc.perform(post("/api/agent/storyboards/image/queue").header("X-User-Id", "u1")
                .contentType("application/json").content("{\"projectId\":\"p1\",\"items\":[{\"sceneId\":\"s1\"}]}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/agent/storyboards/image/queue?projectId=p1").header("X-User-Id", "u1"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/agent/storyboards/image/queue/q1").header("X-User-Id", "u1"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/agent/storyboards/video/queue").header("X-User-Id", "u1")
                .contentType("application/json").content("{\"projectId\":\"p1\",\"items\":[{\"sceneId\":\"s1\"}]}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/agent/storyboards/video/queue?projectId=p1").header("X-User-Id", "u1"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/agent/storyboards/video/queue/q1").header("X-User-Id", "u1"))
            .andExpect(status().isOk());
    }

    @Test
    void turnEndpoints_startStream_andLegacyWritesReturnGone() throws Exception {
        List<Long> emitterTimeouts = new ArrayList<>();
        when(chatService.getOwnedConversation(anyString(), anyString())).thenReturn(conversation());
        doAnswer(inv -> {
            SseEmitter emitter = inv.getArgument(10);
            emitterTimeouts.add(emitter.getTimeout());
            emitter.complete();
            return null;
        })
            .when(chatService).streamAgentScopeTurn(
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(SseEmitter.class));
        doAnswer(inv -> {
            SseEmitter emitter = inv.getArgument(9);
            emitterTimeouts.add(emitter.getTimeout());
            emitter.complete();
            return null;
        })
            .when(chatService).resumeAgentScopeTurn(
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(),
                any(SseEmitter.class));

        mockMvc.perform(post("/api/agent/conversations/c1/turns/stream").header("X-User-Id", "u1")
                .contentType("application/json").content("{\"content\":\"你好\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/agent/conversations/c1/turns/turn-1/resume/stream")
                .header("X-User-Id", "u1")
                .contentType("application/json").content("{\"formToken\":\"token-1\",\"action\":\"approve\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/agent/conversations/c1/messages/stream").header("X-User-Id", "u1")
                        .contentType("application/json").content("{\"content\":\"你好\"}"))
                .andExpect(status().isGone());
        mockMvc.perform(post("/api/agent/conversations/c1/form/submit").header("X-User-Id", "u1")
                        .contentType("application/json").content("{\"action\":\"confirm\",\"taskId\":\"t\"}"))
                .andExpect(status().isGone());
        mockMvc.perform(post("/api/agent/conversations/c1/video/plan/generate").header("X-User-Id", "u1")
                        .contentType("application/json").content("{\"planToken\":\"pt\"}"))
                .andExpect(status().isGone());
        org.assertj.core.api.Assertions.assertThat(emitterTimeouts)
            .containsExactly(14_400_000L, 14_400_000L);
    }

    @Test
    void streamMessage_resolvesSnapshotBySnapshotId() throws Exception {
        when(chatService.getOwnedConversation(anyString(), anyString())).thenReturn(conversation());
        when(assetClient.getSnapshot(anyString(), anyString(), anyString())).thenReturn(
            new AssetContracts.AssetGroupSnapshot(
                "snapshot-1",
                "group-1",
                2,
                "hash",
                "VIDEO",
                List.of(new AssetContracts.SnapshotItem(
                    "asset-1",
                    "media-1",
                    "blob-1",
                    "images/frame.png",
                    "/api/library/media/media-1/content?token=test",
                    AssetContracts.MediaType.IMAGE,
                    AssetContracts.MediaRole.REFERENCE,
                    0
                )),
                java.time.OffsetDateTime.now()
            )
        );
        doAnswer(inv -> {
            SseEmitter emitter = inv.getArgument(10);
            emitter.complete();
            return null;
        }).when(chatService).streamAgentScopeTurn(
            anyString(), anyString(), anyString(), any(), any(), any(), any(), any(),
            any(), any(), any(SseEmitter.class));

        mockMvc.perform(post("/api/agent/conversations/c1/turns/stream")
                .header("X-User-Id", "u1")
                .contentType("application/json")
                .content("""
                    {
                      "content": "请用这个资产组生成视频",
                      "assetGroupIds": ["group-1"],
                      "assetGroupSnapshotIds": ["snapshot-1"]
                    }
                    """))
            .andExpect(status().isOk());

        verify(assetClient).getSnapshot(eq("snapshot-1"), eq("u1"), anyString());
    }
}
