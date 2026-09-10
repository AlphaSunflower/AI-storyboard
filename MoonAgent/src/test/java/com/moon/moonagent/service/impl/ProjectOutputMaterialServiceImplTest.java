package com.moon.moonagent.service.impl;

import com.moon.moonagent.dto.response.ProjectOutputMaterialGroup;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectOutputMaterialServiceImplTest {

    private AgentConversationMapper conversationMapper;
    private AgentAssetMapper assetMapper;
    private ProjectOutputMaterialServiceImpl service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(AgentConversationMapper.class);
        assetMapper = mock(AgentAssetMapper.class);
        service = new ProjectOutputMaterialServiceImpl(conversationMapper, assetMapper);
    }

    @Test
    void list_putsCurrentConversationFirst_andSortsItemsNewestFirst() {
        AgentConversation current = conversation("c-now", "当前对话", "2026-09-10T01:00:00Z");
        AgentConversation older = conversation("c-old", "旧对话", "2026-09-10T05:00:00Z");
        when(conversationMapper.selectList(any())).thenReturn(List.of(older, current));

        AgentAsset oldImage = image("a-old", "c-old", "completed", "2026-09-09T01:00:00Z",
                "/api/files/images/old.png", null);
        AgentAsset currentSecond = image("a-current-2", "c-now", "completed", "2026-09-10T02:00:00Z",
                null, "[\"/api/files/images/p2.png\"]");
        AgentAsset currentFirst = image("a-current-1", "c-now", "completed", "2026-09-10T03:00:00Z",
                null, "[\"/api/files/images/p1.png\"]");
        AgentAsset reference = image("a-ref", "c-now", "completed", "2026-09-10T04:00:00Z",
                "/api/files/images/ref.png", null);
        reference.setType("reference");
        AgentAsset failed = image("a-failed", "c-now", "failed", "2026-09-10T04:00:00Z",
                "/api/files/images/failed.png", null);
        AgentAsset noUrl = image("a-no-url", "c-now", "completed", "2026-09-10T04:00:00Z", null, null);
        AgentAsset editorImage = image("a-editor", null, "completed", "2026-09-10T05:00:00Z",
                "/api/files/images/editor.png", null);
        editorImage.setUserId("u1");
        editorImage.setProjectId("p1");
        when(assetMapper.selectList(any())).thenReturn(List.of(reference, failed, oldImage,
                currentSecond, currentFirst, noUrl, editorImage));

        List<ProjectOutputMaterialGroup> groups = service.list("u1", "p1", "c-now");

        assertThat(groups).extracting(ProjectOutputMaterialGroup::conversationId)
                .containsExactly("__editor__", "c-now", "c-old");
        assertThat(groups.get(1).items()).extracting(item -> item.id())
                .containsExactly("a-current-1", "a-current-2");
        assertThat(groups.get(1).items().getFirst().urls())
                .containsExactly("/api/files/images/p1.png");
        assertThat(groups.getFirst().items()).extracting(item -> item.id())
                .containsExactly("a-editor");
    }

    private static AgentConversation conversation(String id, String title, String updatedAt) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(id);
        conversation.setTitle(title);
        conversation.setUpdatedAt(OffsetDateTime.parse(updatedAt));
        return conversation;
    }

    private static AgentAsset image(String id, String conversationId, String status,
                                    String createdAt, String url, String resultUrls) {
        AgentAsset asset = new AgentAsset();
        asset.setId(id);
        asset.setConversationId(conversationId);
        asset.setType("image");
        asset.setStatus(status);
        asset.setCreatedAt(OffsetDateTime.parse(createdAt));
        asset.setUrl(url);
        asset.setResultUrls(resultUrls);
        return asset;
    }
}
