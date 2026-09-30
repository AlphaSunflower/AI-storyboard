package com.moon.moonagent.ai.agent.creative.impl;

import com.moon.moonagent.ai.agent.creative.TimelineManifest;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentTimelineManifestEntity;
import com.moon.moonagent.mapper.AgentTimelineManifestMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TimelineManifestServiceImplTest {

    @Test
    void persistsManifestAndExposesOrderedSourceUrls() {
        AgentTimelineManifestMapper mapper = mock(AgentTimelineManifestMapper.class);
        TimelineManifestServiceImpl service = new TimelineManifestServiceImpl(mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setProjectId("p1");
        TimelineManifest manifest = new TimelineManifest(
                null, "p1", "c1", "t1", "成片", "draft", 8,
                List.of(
                        new TimelineManifest.Shot(
                                "s2", 2, "/v2.mp4", 4, 4, 1.0, "cut", ""),
                        new TimelineManifest.Shot(
                                "s1", 1, "/v1.mp4", 0, 4, 1.0, "cut", "")),
                OffsetDateTime.now());

        TimelineManifest saved = service.save(conversation, "t1", manifest);

        assertThat(saved.sourceUrls()).containsExactly("/v2.mp4", "/v1.mp4");
        ArgumentCaptor<AgentTimelineManifestEntity> captor =
                ArgumentCaptor.forClass(AgentTimelineManifestEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo("p1");
        assertThat(captor.getValue().getManifestJson()).contains("/v1.mp4");
    }
}
