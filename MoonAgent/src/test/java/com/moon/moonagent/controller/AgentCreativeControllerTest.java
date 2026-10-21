package com.moon.moonagent.controller;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.CreativeCritiqueService;
import com.moon.moonagent.ai.agent.creative.CreativeMode;
import com.moon.moonagent.ai.agent.creative.CreativeRunService;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContext;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.memory.AgentMemoryLimits;
import com.moon.moonagent.ai.agent.memory.AgentMemoryService;
import com.moon.moonagent.dto.request.AgentGlobalMemoryRequest;
import com.moon.moonagent.dto.request.AgentMemorySettingsRequest;
import com.moon.moonagent.dto.request.CreativeContextUpdateRequest;
import com.moon.moonagent.entity.AgentGlobalMemory;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentCreativeControllerTest {

    @Test
    void contextEndpointsUseAuthenticatedOwner() {
        ProjectCreativeContextService contexts = mock(ProjectCreativeContextService.class);
        ProjectCreativeContext context = new ProjectCreativeContext(
                "p1", "u1", "目标", "方向", "风格",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), 2, OffsetDateTime.now());
        when(contexts.get("u1", "p1")).thenReturn(context);
        when(contexts.patch(eq("u1"), eq("p1"), any())).thenReturn(context);
        AgentCreativeController controller = new AgentCreativeController(
                contexts, mock(AgentMemoryService.class),
                mock(CreativeRunService.class), mock(CreativeCritiqueService.class),
                mock(AgentChatService.class), new AgentAiConfigProperties());
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("u1");

        var read = controller.getCreativeContext(auth, "p1");
        var written = controller.updateCreativeContext(
                auth, "p1", new CreativeContextUpdateRequest(
                        "目标", null, null, null, null, null, null, null, null, null));

        assertThat(read.data().projectId()).isEqualTo("p1");
        assertThat(written.data().version()).isEqualTo(2);
        verify(contexts).get("u1", "p1");
        verify(contexts).patch(eq("u1"), eq("p1"), any());
    }

    @Test
    void memorySettingsExposeSwitchGlobalMemoryAndLimits() {
        AgentMemoryService memories = mock(AgentMemoryService.class);
        AgentGlobalMemory global = new AgentGlobalMemory();
        global.setContent("所有项目都用中文回复");
        global.setUpdatedAt(OffsetDateTime.now());
        when(memories.enabled("u1")).thenReturn(true);
        when(memories.global("u1")).thenReturn(global);
        when(memories.limits()).thenReturn(AgentMemoryLimits.DEFAULTS);
        AgentCreativeController controller = new AgentCreativeController(
                mock(ProjectCreativeContextService.class), memories,
                mock(CreativeRunService.class), mock(CreativeCritiqueService.class),
                mock(AgentChatService.class), new AgentAiConfigProperties());
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("u1");

        var settings = controller.memorySettings(auth).data();

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.global().content()).isEqualTo("所有项目都用中文回复");
        assertThat(settings.limits().project()).isEqualTo(AgentMemoryLimits.PROJECT_MAX);
    }

    @Test
    void updateMemorySettingsTogglesTheSwitch() {
        AgentMemoryService memories = mock(AgentMemoryService.class);
        when(memories.enabled("u1")).thenReturn(false);
        when(memories.limits()).thenReturn(AgentMemoryLimits.DEFAULTS);
        AgentCreativeController controller = new AgentCreativeController(
                mock(ProjectCreativeContextService.class), memories,
                mock(CreativeRunService.class), mock(CreativeCritiqueService.class),
                mock(AgentChatService.class), new AgentAiConfigProperties());
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("u1");

        var settings = controller.updateMemorySettings(
                auth, new AgentMemorySettingsRequest(false)).data();

        assertThat(settings.enabled()).isFalse();
        verify(memories).setEnabled("u1", false);
    }

    @Test
    void saveGlobalMemoryDelegatesToService() {
        AgentMemoryService memories = mock(AgentMemoryService.class);
        AgentGlobalMemory saved = new AgentGlobalMemory();
        saved.setContent("所有项目都用中文回复");
        saved.setUpdatedAt(OffsetDateTime.now());
        when(memories.saveGlobal("u1", "所有项目都用中文回复")).thenReturn(saved);
        AgentCreativeController controller = new AgentCreativeController(
                mock(ProjectCreativeContextService.class), memories,
                mock(CreativeRunService.class), mock(CreativeCritiqueService.class),
                mock(AgentChatService.class), new AgentAiConfigProperties());
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("u1");

        var response = controller.saveGlobalMemory(
                auth, new AgentGlobalMemoryRequest("所有项目都用中文回复"));

        assertThat(response.data().content()).isEqualTo("所有项目都用中文回复");
        verify(memories).saveGlobal("u1", "所有项目都用中文回复");
    }

    @Test
    void repairStreamsANewTurnForTheOwnedConversation() {
        CreativeRunService runs = mock(CreativeRunService.class);
        AgentChatService chat = mock(AgentChatService.class);
        when(runs.repairTarget("u1", "turn-1", "item-1", "更自然"))
                .thenReturn(new CreativeRunService.RepairTarget(
                        "c1", "repair prompt", List.of("/bad.png")));
        AgentCreativeController controller = new AgentCreativeController(
                mock(ProjectCreativeContextService.class), mock(AgentMemoryService.class), runs,
                mock(CreativeCritiqueService.class), chat, new AgentAiConfigProperties());
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("u1");

        SseEmitter emitter = controller.repair(auth, "turn-1", "item-1", "更自然");

        assertThat(emitter).isNotNull();
        verify(chat).streamAgentScopeTurn(
                eq("u1"), eq("c1"), eq("repair prompt"), eq(List.of("/bad.png")),
                eq(List.of()), isNull(), isNull(), isNull(), eq(List.of()), eq(List.of()),
                same(emitter));
    }
}
