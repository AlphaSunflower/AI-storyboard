package com.moon.moonagent.ai.agent.creative.impl;

import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.CreativeMode;
import com.moon.moonagent.ai.agent.creative.CreativeModelRouter;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMediaCritiqueEntity;
import com.moon.moonagent.mapper.AgentMediaCritiqueMapper;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CreativeCritiqueServiceImplTest {

    @Test
    void missingEvidenceDegradesToUnverifiedInsteadOfFalseSuccess() {
        AgentMediaCritiqueMapper mapper = mock(AgentMediaCritiqueMapper.class);
        CreativeCritiqueServiceImpl service = new CreativeCritiqueServiceImpl(
                mapper,
                mock(FileStorageService.class),
                mock(ChatClient.Builder.class),
                mock(CreativeModelRouter.class));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setProjectId("p1");

        var critique = service.reviewImages(
                conversation, "t1", "item1", "生成图片", List.of(),
                new ConstraintLedger(CreativeMode.BALANCED, List.of(), List.of(), List.of()),
                0);

        assertThat(critique.decision()).isEqualTo("UNVERIFIED");
        assertThat(critique.hardConstraintPass()).isFalse();
        assertThat(critique.reasons()).anyMatch(reason -> reason.contains("未执行"));
        verify(mapper).insert(any(AgentMediaCritiqueEntity.class));
    }

    @Test
    void missingVideoUrlDoesNotFabricateValidation() {
        AgentMediaCritiqueMapper mapper = mock(AgentMediaCritiqueMapper.class);
        CreativeCritiqueServiceImpl service = new CreativeCritiqueServiceImpl(
                mapper,
                mock(FileStorageService.class),
                mock(ChatClient.Builder.class),
                mock(CreativeModelRouter.class));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setProjectId("p1");

        var critique = service.reviewVideo(
                conversation, "t1", "item1", "生成视频", "",
                new ConstraintLedger(CreativeMode.BALANCED, List.of(), List.of(), List.of()),
                0);

        assertThat(critique.mediaType()).isEqualTo("video");
        assertThat(critique.decision()).isEqualTo("UNVERIFIED");
        assertThat(critique.hardConstraintPass()).isFalse();
        verify(mapper).insert(any(AgentMediaCritiqueEntity.class));
    }
}
