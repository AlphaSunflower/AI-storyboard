package com.moon.moonagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.dto.response.ConversationAssetGroupVO;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentConversationAssetGroup;
import com.moon.moonagent.mapper.AgentConversationAssetGroupMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.service.ConversationAssetGroupService;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.storyboard.asset.client.AssetContracts.SnapshotRequest;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ConversationAssetGroupServiceImpl implements ConversationAssetGroupService {

    private static final String TARGET = "AGENT";

    private final AgentConversationMapper conversationMapper;
    private final AgentConversationAssetGroupMapper bindingMapper;
    private final AssetClient assetClient;

    public ConversationAssetGroupServiceImpl(
            AgentConversationMapper conversationMapper,
            AgentConversationAssetGroupMapper bindingMapper,
            AssetClient assetClient
    ) {
        this.conversationMapper = conversationMapper;
        this.bindingMapper = bindingMapper;
        this.assetClient = assetClient;
    }

    @Override
    public List<ConversationAssetGroupVO> list(String userId, String conversationId) {
        requireConversation(userId, conversationId);
        return bindings(conversationId).stream().map(binding -> {
            AssetGroupSnapshot latest = createSnapshot(userId, binding.getGroupId());
            AssetGroupSnapshot bound = assetClient.getSnapshot(
                    binding.getSnapshotId(), userId, UUID.randomUUID().toString());
            boolean stale = latest.snapshotId() != null
                    && !latest.snapshotId().equals(binding.getSnapshotId());
            return toVO(binding, bound, stale);
        }).toList();
    }

    @Override
    @Transactional
    public ConversationAssetGroupVO attach(String userId, String conversationId, String groupId) {
        requireConversation(userId, conversationId);
        if (groupId == null || groupId.isBlank()) {
            throw new BusinessException(40001, "资产组 ID 不能为空");
        }
        AssetGroupSnapshot snapshot = createSnapshot(userId, groupId);
        AgentConversationAssetGroup binding = binding(conversationId, groupId);
        if (binding == null) {
            binding = new AgentConversationAssetGroup();
            binding.setConversationId(conversationId);
            binding.setGroupId(groupId);
            binding.setSnapshotId(snapshot.snapshotId());
            binding.setGroupVersion(snapshot.groupVersion());
            binding.setContentHash(snapshot.contentHash());
            binding.setTarget(TARGET);
            bindingMapper.insert(binding);
        } else {
            binding.setSnapshotId(snapshot.snapshotId());
            binding.setGroupVersion(snapshot.groupVersion());
            binding.setContentHash(snapshot.contentHash());
            binding.setTarget(TARGET);
            bindingMapper.updateById(binding);
        }
        return toVO(binding, snapshot, false);
    }

    @Override
    @Transactional
    public void remove(String userId, String conversationId, String groupId) {
        requireConversation(userId, conversationId);
        bindingMapper.delete(new LambdaQueryWrapper<AgentConversationAssetGroup>()
                .eq(AgentConversationAssetGroup::getConversationId, conversationId)
                .eq(AgentConversationAssetGroup::getGroupId, groupId));
    }

    private AgentConversation requireConversation(String userId, String conversationId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            throw new BusinessException(40401, "会话不存在或无权访问");
        }
        return conversation;
    }

    private List<AgentConversationAssetGroup> bindings(String conversationId) {
        return bindingMapper.selectList(new LambdaQueryWrapper<AgentConversationAssetGroup>()
                .eq(AgentConversationAssetGroup::getConversationId, conversationId)
                .orderByAsc(AgentConversationAssetGroup::getCreatedAt));
    }

    private AgentConversationAssetGroup binding(String conversationId, String groupId) {
        return bindingMapper.selectOne(new LambdaQueryWrapper<AgentConversationAssetGroup>()
                .eq(AgentConversationAssetGroup::getConversationId, conversationId)
                .eq(AgentConversationAssetGroup::getGroupId, groupId)
                .last("LIMIT 1"));
    }

    private AssetGroupSnapshot createSnapshot(String userId, String groupId) {
        return assetClient.createSnapshot(
                groupId,
                new SnapshotRequest(userId, TARGET, 9, 3, 3),
                UUID.randomUUID().toString());
    }

    private static ConversationAssetGroupVO toVO(
            AgentConversationAssetGroup binding,
            AssetGroupSnapshot bound,
            boolean stale
    ) {
        List<ConversationAssetGroupVO.SnapshotMediaVO> media = bound == null || bound.items() == null
                ? List.of()
                : bound.items().stream()
                        .map(item -> new ConversationAssetGroupVO.SnapshotMediaVO(
                                item.type().name(), item.role().name(), item.contentUrl()))
                        .toList();
        return new ConversationAssetGroupVO(
                binding.getGroupId(),
                binding.getSnapshotId(),
                binding.getGroupVersion() == null ? 0 : binding.getGroupVersion(),
                binding.getContentHash(),
                binding.getTarget(),
                stale,
                media
        );
    }
}
