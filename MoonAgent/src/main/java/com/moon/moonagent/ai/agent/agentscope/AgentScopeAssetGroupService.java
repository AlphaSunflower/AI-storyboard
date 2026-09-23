package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentConversationAssetGroup;
import com.moon.moonagent.mapper.AgentConversationAssetGroupMapper;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts.AssetGroupDetail;
import com.storyboard.asset.client.AssetContracts.AssetGroupMediaItem;
import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.storyboard.asset.client.AssetContracts.SnapshotItem;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
class AgentScopeAssetGroupService {

    private final AgentConversationAssetGroupMapper bindingMapper;
    private final AssetClient assetClient;

    List<AgentScopeAssetGroupRef> list(AgentConversation conversation, String requestedGroupId) {
        if (conversation == null || conversation.getId() == null) return List.of();
        List<AgentConversationAssetGroup> bindings = bindingMapper.selectList(
                new LambdaQueryWrapper<AgentConversationAssetGroup>()
                        .eq(AgentConversationAssetGroup::getConversationId, conversation.getId())
                        .orderByAsc(AgentConversationAssetGroup::getCreatedAt));
        return bindings.stream()
                .filter(binding -> requestedGroupId == null || requestedGroupId.isBlank()
                        || requestedGroupId.equals(binding.getGroupId()))
                .map(binding -> resolve(conversation.getUserId(), binding))
                .filter(ref -> ref != null)
                .toList();
    }

    List<String> imageContentUrls(AgentConversation conversation) {
        return list(conversation, "").stream()
                .flatMap(group -> group.files().stream())
                .filter(file -> "IMAGE".equalsIgnoreCase(file.type()))
                .map(AgentScopeAssetGroupRef.FileRef::contentUrl)
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();
    }

    boolean hasBoundGroups(AgentConversation conversation) {
        if (conversation == null || conversation.getId() == null
                || conversation.getId().isBlank()) {
            return false;
        }
        Long count = bindingMapper.selectCount(
                new LambdaQueryWrapper<AgentConversationAssetGroup>()
                        .eq(AgentConversationAssetGroup::getConversationId,
                                conversation.getId()));
        return count != null && count > 0;
    }

    private AgentScopeAssetGroupRef resolve(String userId, AgentConversationAssetGroup binding) {
        try {
            String correlationId = UUID.randomUUID().toString();
            AssetGroupDetail detail = assetClient.getGroupInternal(binding.getGroupId(), userId);
            AssetGroupSnapshot snapshot = assetClient.getSnapshot(
                    binding.getSnapshotId(), userId, correlationId);
            Map<String, SnapshotItem> snapshotByMedia = snapshot.items() == null
                    ? Map.of()
                    : snapshot.items().stream().collect(Collectors.toMap(
                            SnapshotItem::mediaId,
                            Function.identity(),
                            (left, right) -> left));
            List<AgentScopeAssetGroupRef.FileRef> files = detail.media() == null
                    ? List.of()
                    : detail.media().stream()
                            .filter(item -> item.media() != null)
                            .map(item -> toFile(item, snapshotByMedia.get(item.media().id())))
                            .toList();
            return new AgentScopeAssetGroupRef(
                    binding.getGroupId(),
                    binding.getSnapshotId(),
                    detail.name(),
                    detail.description(),
                    detail.version(),
                    files);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static AgentScopeAssetGroupRef.FileRef toFile(
            AssetGroupMediaItem item,
            SnapshotItem snapshotItem) {
        String contentUrl = snapshotItem != null && snapshotItem.contentUrl() != null
                ? snapshotItem.contentUrl() : item.media().contentUrl();
        return new AgentScopeAssetGroupRef.FileRef(
                item.assetId(),
                item.assetName(),
                item.assetDescription(),
                item.media().id(),
                item.media().type() == null ? "" : item.media().type().name(),
                item.media().role() == null ? "" : item.media().role().name(),
                item.media().fileName(),
                item.media().mimeType(),
                item.media().objectKey(),
                item.media().constraintText(),
                contentUrl);
    }
}
