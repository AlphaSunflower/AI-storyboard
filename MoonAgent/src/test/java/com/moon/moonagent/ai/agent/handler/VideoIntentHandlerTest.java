package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.VideoPlanService;
import com.moon.moonagent.ai.agent.PlanField;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoIntentHandlerTest {

    @Mock
    private AgentOrchestratorSupport support;

    @Mock
    private VideoPlanService videoPlanService;

    @Mock
    private StoryboardClient storyboardClient;

    @InjectMocks
    private VideoIntentHandler videoIntentHandler;

    private AgentConversation conversation;
    private OrchestrationRequest request;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        conversation = new AgentConversation();
        conversation.setId("conv-1");
        conversation.setProjectId("proj-1");
        emitter = new SseEmitter();
        request = new OrchestrationRequest(conversation, "测试视频需求", List.of(), emitter);
    }

    // ── 语义角色 → 逐图 ReferenceImage 拆解 ──

    @Test
    void buildReferenceImages_reference_allReference() {
        List<ReferenceImage> out = VideoIntentHandler.buildReferenceImages("REFERENCE", List.of("u1", "u2", "u3"));
        assertEquals(3, out.size());
        assertEquals(ReferenceImageRole.REFERENCE, out.get(0).role());
        assertEquals(ReferenceImageRole.REFERENCE, out.get(2).role());
    }

    @Test
    void buildReferenceImages_firstFrame_firstOnly() {
        List<ReferenceImage> out = VideoIntentHandler.buildReferenceImages("FIRST_FRAME", List.of("u1", "u2"));
        assertEquals(ReferenceImageRole.FIRST_FRAME, out.get(0).role());
        assertEquals(ReferenceImageRole.REFERENCE, out.get(1).role());
    }

    @Test
    void buildReferenceImages_lastFrame_lastOnly() {
        List<ReferenceImage> out = VideoIntentHandler.buildReferenceImages("LAST_FRAME", List.of("u1", "u2"));
        assertEquals(ReferenceImageRole.REFERENCE, out.get(0).role());
        assertEquals(ReferenceImageRole.LAST_FRAME, out.get(1).role());
    }

    @Test
    void buildReferenceImages_firstLastFrame_twoImagesSplit() {
        List<ReferenceImage> out = VideoIntentHandler.buildReferenceImages("FIRST_LAST_FRAME", List.of("u1", "u2"));
        assertEquals(ReferenceImageRole.FIRST_FRAME, out.get(0).role());
        assertEquals(ReferenceImageRole.LAST_FRAME, out.get(1).role());
        assertTrue(out.get(0).url().equals("u1") && out.get(1).url().equals("u2"));
    }

    @Test
    void buildReferenceImages_firstLastFrame_singleImageFallsBackToFirstFrame() {
        List<ReferenceImage> out = VideoIntentHandler.buildReferenceImages("FIRST_LAST_FRAME", List.of("u1"));
        assertEquals(1, out.size());
        assertEquals(ReferenceImageRole.FIRST_FRAME, out.get(0).role());
    }

    @Test
    void buildReferenceImages_invalidRole_allReference() {
        List<ReferenceImage> out = VideoIntentHandler.buildReferenceImages("BOGUS", List.of("u1", "u2"));
        assertEquals(ReferenceImageRole.REFERENCE, out.get(0).role());
        assertEquals(ReferenceImageRole.REFERENCE, out.get(1).role());
    }

    @Test
    void buildReferenceImages_emptyUrls_emptyList() {
        assertTrue(VideoIntentHandler.buildReferenceImages("FIRST_FRAME", List.of()).isEmpty());
    }

    // ── resume generate_video：自动推荐角色 → 带 role 异步生成，不再发角色选择卡 ──

    @Test
    void resume_generateVideo_usesDecomposedReferenceImages() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setAction("generate_video");
        cp.setPlan("{\"items\":[{}]}");

        when(support.planField(cp.getPlan(), PlanField.MESSAGE)).thenReturn("测试视频方案");
        when(support.planField(cp.getPlan(), PlanField.DURATION)).thenReturn("8");
        when(support.planPicUrls(cp.getPlan())).thenReturn(List.of("u1", "u2"));
        when(support.planField(cp.getPlan(), PlanField.ASSET_IDS)).thenReturn("");
        when(support.assetImageUrls("proj-1", "")).thenReturn(List.of());
        when(support.planField(cp.getPlan(), "referenceRole")).thenReturn("FIRST_LAST_FRAME");

        String result = videoIntentHandler.resume(request, cp);

        assertEquals("", result);
        ArgumentCaptor<List<ReferenceImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(support).startVideoGenerationAsyncWithRole(eq(request), eq("测试视频方案"), eq("8"),
                isNull(), captor.capture(), eq(request.getParams()));
        List<ReferenceImage> refs = captor.getValue();
        assertEquals(2, refs.size());
        assertEquals("u1", refs.get(0).url());
        assertEquals(ReferenceImageRole.FIRST_FRAME, refs.get(0).role());
        assertEquals("u2", refs.get(1).url());
        assertEquals(ReferenceImageRole.LAST_FRAME, refs.get(1).role());
    }
}
