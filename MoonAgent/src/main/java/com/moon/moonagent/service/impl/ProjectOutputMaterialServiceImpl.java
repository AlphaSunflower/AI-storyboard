package com.moon.moonagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.dto.response.ProjectOutputMaterialGroup;
import com.moon.moonagent.dto.response.ProjectOutputMaterialItem;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.service.ProjectOutputMaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProjectOutputMaterialServiceImpl implements ProjectOutputMaterialService {

    private final AgentConversationMapper conversationMapper;
    private final AgentAssetMapper assetMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<ProjectOutputMaterialGroup> list(String userId, String projectId, String currentConversationId) {
        List<AgentConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getProjectId, projectId));
        if (conversations == null || conversations.isEmpty()) return List.of();

        List<String> ids = conversations.stream().map(AgentConversation::getId).toList();
        List<AgentAsset> records = assetMapper.selectList(
                new LambdaQueryWrapper<AgentAsset>()
                        .in(AgentAsset::getConversationId, ids)
                        .in(AgentAsset::getType, List.of("image", "video"))
                        .eq(AgentAsset::getStatus, "completed")
                        .orderByDesc(AgentAsset::getCreatedAt));

        Map<String, List<ProjectOutputMaterialItem>> byConversation = new LinkedHashMap<>();
        for (AgentAsset record : records == null ? List.<AgentAsset>of() : records) {
            if (!"completed".equals(record.getStatus())
                    || (!"image".equals(record.getType()) && !"video".equals(record.getType()))) {
                continue;
            }
            List<String> urls = resultUrls(record);
            if (urls.isEmpty() || record.getConversationId() == null) continue;
            byConversation.computeIfAbsent(record.getConversationId(), ignored -> new ArrayList<>())
                    .add(new ProjectOutputMaterialItem(
                            record.getId(), record.getType(), urls,
                            record.getSceneId(), record.getCreatedAt()));
        }
        byConversation.values().forEach(items -> items.sort(Comparator
                .comparing(ProjectOutputMaterialItem::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))));

        List<AgentConversation> ordered = new ArrayList<>(conversations);
        ordered.sort(Comparator
                .comparing((AgentConversation c) -> !c.getId().equals(currentConversationId))
                .thenComparing(AgentConversation::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));

        List<ProjectOutputMaterialGroup> groups = new ArrayList<>();
        for (AgentConversation conversation : ordered) {
            List<ProjectOutputMaterialItem> items = byConversation.get(conversation.getId());
            if (items == null || items.isEmpty()) continue;
            groups.add(new ProjectOutputMaterialGroup(
                    conversation.getId(), conversation.getTitle(),
                    conversation.getUpdatedAt(), List.copyOf(items)));
        }
        return groups;
    }

    private List<String> resultUrls(AgentAsset record) {
        List<String> urls = new ArrayList<>();
        if (record.getResultUrls() != null && !record.getResultUrls().isBlank()) {
            try {
                List<String> parsed = objectMapper.readValue(record.getResultUrls(), new TypeReference<>() {});
                parsed.stream().filter(url -> url != null && !url.isBlank()).forEach(urls::add);
            } catch (Exception ignored) {
                // 宽松兼容历史坏数据，下行走 url 单字段兜底
            }
        }
        if (urls.isEmpty() && record.getUrl() != null && !record.getUrl().isBlank()) {
            urls.add(record.getUrl());
        }
        return urls.stream().distinct().toList();
    }
}
