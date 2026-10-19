package com.moon.moonagent.service.impl;

import com.moon.moonagent.dto.response.ConversationAssetGroupVO;
import com.moon.moonagent.dto.response.ProjectAssetGroupBindingVO;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentConversationAssetGroup;
import com.moon.moonagent.mapper.AgentConversationAssetGroupMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.SnapshotItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationAssetGroupServiceImplTest {

    private AgentConversationMapper conversationMapper;
    private AgentConversationAssetGroupMapper bindingMapper;
    private AssetClient assetClient;
    private ConversationAssetGroupServiceImpl service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(AgentConversationMapper.class);
        bindingMapper = mock(AgentConversationAssetGroupMapper.class);
        assetClient = mock(AssetClient.class);
        service = new ConversationAssetGroupServiceImpl(conversationMapper, bindingMapper, assetClient);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        when(conversationMapper.selectById("c1")).thenReturn(conversation);
    }

    @Test
    void attachFreezesSnapshotAndPersistsBinding() {
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(assetClient.createSnapshot(any(), any(), any())).thenReturn(snapshot("s1", 2));

        ConversationAssetGroupVO binding = service.attach("u1", "c1", "g1");

        assertThat(binding.groupId()).isEqualTo("g1");
        assertThat(binding.snapshotId()).isEqualTo("s1");
        assertThat(binding.stale()).isFalse();
        assertThat(binding.media()).hasSize(1);
        verify(bindingMapper).insert(any(AgentConversationAssetGroup.class));
    }

    @Test
    void listReportsStaleWithoutReplacingBoundSnapshot() {
        AgentConversationAssetGroup binding = new AgentConversationAssetGroup();
        binding.setConversationId("c1");
        binding.setGroupId("g1");
        binding.setSnapshotId("s1");
        binding.setGroupVersion(1L);
        binding.setContentHash("old");
        binding.setTarget("AGENT");
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(assetClient.getSnapshot(any(), any(), any())).thenReturn(snapshot("s1", 1));
        when(assetClient.createSnapshot(any(), any(), any())).thenReturn(snapshot("s2", 2));

        List<ConversationAssetGroupVO> result = service.list("u1", "c1");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().snapshotId()).isEqualTo("s1");
        assertThat(result.getFirst().stale()).isTrue();
    }

    @Test
    void listProjectBindingsReturnsConversationTitlesAndFrozenVersions() {
        AgentConversation first = new AgentConversation();
        first.setId("c1");
        first.setUserId("u1");
        first.setProjectId("p1");
        first.setTitle("分镜 A");
        AgentConversation second = new AgentConversation();
        second.setId("c2");
        second.setUserId("u1");
        second.setProjectId("p1");
        second.setTitle("分镜 B");
        when(conversationMapper.selectList(any())).thenReturn(List.of(first, second));

        AgentConversationAssetGroup bindingOne = new AgentConversationAssetGroup();
        bindingOne.setConversationId("c1");
        bindingOne.setGroupId("g1");
        bindingOne.setSnapshotId("s1");
        bindingOne.setGroupVersion(2L);
        bindingOne.setTarget("AGENT");
        bindingOne.setUpdatedAt(OffsetDateTime.now());
        AgentConversationAssetGroup bindingTwo = new AgentConversationAssetGroup();
        bindingTwo.setConversationId("c2");
        bindingTwo.setGroupId("g1");
        bindingTwo.setSnapshotId("s0");
        bindingTwo.setGroupVersion(1L);
        bindingTwo.setTarget("AGENT");
        bindingTwo.setUpdatedAt(OffsetDateTime.now());
        when(bindingMapper.selectList(any())).thenReturn(List.of(bindingOne, bindingTwo));

        List<ProjectAssetGroupBindingVO> bindings = service.listProjectBindings("u1", "p1");

        assertThat(bindings).hasSize(2);
        assertThat(bindings)
                .extracting(ProjectAssetGroupBindingVO::conversationTitle)
                .containsExactly("分镜 A", "分镜 B");
        assertThat(bindings)
                .extracting(ProjectAssetGroupBindingVO::groupVersion)
                .containsExactly(2L, 1L);
    }

    @Test
    void listProjectBindingsSkipsProjectWithoutConversations() {
        when(conversationMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listProjectBindings("u1", "p1")).isEmpty();
    }

    private static AssetGroupSnapshot snapshot(String id, long version) {
        return new AssetGroupSnapshot(
                id, "g1", version, "hash-" + version, "AGENT",
                List.of(new SnapshotItem(
                        "a1", "m1", "b1", "images/a.png", "/api/library/media/m1/content",
                        MediaType.IMAGE, MediaRole.REFERENCE, 0)),
                OffsetDateTime.now());
    }
}
