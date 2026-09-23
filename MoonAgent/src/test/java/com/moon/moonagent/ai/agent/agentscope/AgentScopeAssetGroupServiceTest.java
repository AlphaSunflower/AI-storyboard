package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentConversationAssetGroup;
import com.moon.moonagent.mapper.AgentConversationAssetGroupMapper;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts.AssetGroupDetail;
import com.storyboard.asset.client.AssetContracts.AssetGroupMediaItem;
import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.SnapshotItem;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeAssetGroupServiceTest {

    @Test
    void returnsGroupMetadataConstraintsFileNamesAndReadableUrls() {
        AgentConversationAssetGroupMapper mapper = mock(AgentConversationAssetGroupMapper.class);
        AssetClient client = mock(AssetClient.class);
        AgentConversationAssetGroup binding = new AgentConversationAssetGroup();
        binding.setGroupId("group-1");
        binding.setSnapshotId("snapshot-1");
        when(mapper.selectList(any())).thenReturn(List.of(binding));
        when(client.getGroupInternal("group-1", "user-1")).thenReturn(new AssetGroupDetail(
                "group-1", "四宫格素材", "四张人物参考图", 3,
                List.of(new AssetGroupMediaItem(
                        media("media-1", "first.png", "人物正面"),
                        "asset-1", "人物A", "棕色短发、金属框眼镜", 2, List.of()))));
        when(client.getSnapshot(eq("snapshot-1"), eq("user-1"), any()))
                .thenReturn(new AssetGroupSnapshot(
                        "snapshot-1", "group-1", 3, "hash", "AGENT",
                        List.of(new SnapshotItem(
                                "asset-1", "media-1", "blob-1", "images/first.png",
                                "/api/library/media/media-1/content?token=signed",
                                MediaType.IMAGE, MediaRole.REFERENCE, 0)),
                        OffsetDateTime.now()));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");

        var groups = new AgentScopeAssetGroupService(mapper, client).list(conversation, "");

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().name()).isEqualTo("四宫格素材");
        assertThat(groups.getFirst().description()).isEqualTo("四张人物参考图");
        assertThat(groups.getFirst().files()).singleElement().satisfies(file -> {
            assertThat(file.assetId()).isEqualTo("asset-1");
            assertThat(file.assetDescription()).isEqualTo("棕色短发、金属框眼镜");
            assertThat(file.fileName()).isEqualTo("first.png");
            assertThat(file.constraintText()).isEqualTo("人物正面");
            assertThat(file.contentUrl()).contains("token=signed");
        });
    }

    @Test
    void detectsConversationAssetBindingsWithoutResolvingRemoteContent() {
        AgentConversationAssetGroupMapper mapper = mock(AgentConversationAssetGroupMapper.class);
        AssetClient client = mock(AssetClient.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");

        assertThat(new AgentScopeAssetGroupService(mapper, client).hasBoundGroups(conversation))
                .isTrue();
    }

    private static MediaRef media(String id, String fileName, String constraint) {
        return new MediaRef(
                id, "blob-" + id, MediaType.IMAGE, "/content/" + id,
                "/api/library/media/" + id + "/content", "images/" + fileName,
                fileName, fileName, "image/png", 100, 512, 512, null,
                0, MediaRole.REFERENCE, true, constraint);
    }
}
