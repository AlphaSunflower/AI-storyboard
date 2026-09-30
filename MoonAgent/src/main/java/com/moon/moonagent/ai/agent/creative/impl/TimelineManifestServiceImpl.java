package com.moon.moonagent.ai.agent.creative.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.creative.TimelineManifest;
import com.moon.moonagent.ai.agent.creative.TimelineManifestService;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentTimelineManifestEntity;
import com.moon.moonagent.mapper.AgentTimelineManifestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class TimelineManifestServiceImpl implements TimelineManifestService {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final AgentTimelineManifestMapper mapper;

    @Override
    @Transactional
    public TimelineManifest save(AgentConversation conversation, String turnId,
                                 TimelineManifest manifest) {
        if (conversation == null) throw new IllegalArgumentException("conversation is required");
        AgentTimelineManifestEntity entity = manifest.id() == null || manifest.id().isBlank()
                ? new AgentTimelineManifestEntity() : mapper.selectById(manifest.id());
        if (entity == null) entity = new AgentTimelineManifestEntity();
        entity.setProjectId(conversation.getProjectId());
        entity.setConversationId(conversation.getId());
        entity.setTurnId(blankToNull(turnId));
        entity.setTitle(text(manifest.title()));
        entity.setStatus(text(manifest.status()).isBlank() ? "draft" : manifest.status());
        entity.setTotalDurationSeconds(Math.max(0, manifest.totalDurationSeconds()));
        entity.setManifestJson(write(manifest));
        entity.setUpdatedAt(OffsetDateTime.now());
        if (entity.getId() == null || entity.getId().isBlank()) mapper.insert(entity);
        else mapper.updateById(entity);
        return toView(entity);
    }

    @Override
    public TimelineManifest get(String projectId, String conversationId, String manifestId) {
        AgentTimelineManifestEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<AgentTimelineManifestEntity>()
                        .eq(AgentTimelineManifestEntity::getId, manifestId)
                        .eq(AgentTimelineManifestEntity::getProjectId, projectId)
                        .eq(AgentTimelineManifestEntity::getConversationId, conversationId)
                        .last("LIMIT 1"));
        return entity == null ? null : toView(entity);
    }

    @Override
    public TimelineManifest latest(String projectId, String conversationId) {
        AgentTimelineManifestEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<AgentTimelineManifestEntity>()
                        .eq(AgentTimelineManifestEntity::getProjectId, projectId)
                        .eq(AgentTimelineManifestEntity::getConversationId, conversationId)
                        .orderByDesc(AgentTimelineManifestEntity::getUpdatedAt)
                        .last("LIMIT 1"));
        return entity == null ? null : toView(entity);
    }

    private static TimelineManifest toView(AgentTimelineManifestEntity entity) {
        try {
            TimelineManifest stored = JSON.readValue(entity.getManifestJson(), TimelineManifest.class);
            return new TimelineManifest(
                    entity.getId(), entity.getProjectId(), entity.getConversationId(),
                    entity.getTurnId(), entity.getTitle(), entity.getStatus(),
                    entity.getTotalDurationSeconds() == null ? 0 : entity.getTotalDurationSeconds(),
                    stored.shots(), entity.getUpdatedAt());
        } catch (Exception ignored) {
            return new TimelineManifest(
                    entity.getId(), entity.getProjectId(), entity.getConversationId(),
                    entity.getTurnId(), entity.getTitle(), entity.getStatus(),
                    entity.getTotalDurationSeconds() == null ? 0 : entity.getTotalDurationSeconds(),
                    java.util.List.of(), entity.getUpdatedAt());
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? java.util.Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("timeline manifest serialization failed", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
