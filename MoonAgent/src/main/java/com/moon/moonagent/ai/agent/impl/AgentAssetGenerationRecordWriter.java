package com.moon.moonagent.ai.agent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.asset.AssetOutputOutboxService;
import com.storyboard.common.generation.GenerationRecord;
import com.storyboard.common.generation.GenerationRecordUpdate;
import com.storyboard.common.generation.GenerationRecordWriter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

/** Moon Agent 的生成记录适配器。 */
@Component
@RequiredArgsConstructor
public class AgentAssetGenerationRecordWriter implements GenerationRecordWriter {

    private static final Logger log = LoggerFactory.getLogger(AgentAssetGenerationRecordWriter.class);

    private final AgentAssetMapper assetMapper;
    private final AssetOutputOutboxService assetOutputOutboxService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String create(GenerationRecord source) {
        AgentAsset record = new AgentAsset();
        record.setConversationId(source.conversationId());
        record.setUserId(source.userId());
        record.setProjectId(source.projectId());
        record.setSceneId(source.sceneId());
        record.setType(source.type());
        record.setGenType(source.genType());
        record.setPrompt(source.prompt());
        record.setModel(source.model());
        record.setTaskId(source.taskId());
        record.setUrl(source.url());
        record.setStatus(source.status());
        record.setError(source.error());
        record.setDurationMs(source.durationMs());
        record.setCreatedAt(source.createdAt());
        if (!source.resultUrls().isEmpty()) record.setResultUrls(toJson(source.resultUrls()));
        if (!source.referenceUrls().isEmpty()) record.setReferenceUrls(toJson(source.referenceUrls()));
        if (!source.params().isEmpty()) record.setParams(toJson(source.params()));
        assetMapper.insert(record);
        assetOutputOutboxService.enqueue(record);
        return record.getId();
    }

    @Override
    public void update(String recordId, GenerationRecordUpdate update) {
        if (recordId == null || recordId.isBlank()) return;
        AgentAsset record = assetMapper.selectById(recordId);
        if (record == null || isDeleted(record)) return;
        apply(record, update);
        assetMapper.updateById(record);
        assetOutputOutboxService.enqueue(record);
    }

    @Override
    public void attachTaskId(String recordId, String taskId) {
        if (recordId == null || recordId.isBlank() || taskId == null || taskId.isBlank()) return;
        AgentAsset record = assetMapper.selectById(recordId);
        if (record == null) return;
        record.setTaskId(taskId);
        assetMapper.updateById(record);
    }

    @Override
    public void updateByTaskId(String taskId, GenerationRecordUpdate update) {
        if (taskId == null || taskId.isBlank()) return;
        var records = assetMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentAsset>()
                        .eq(AgentAsset::getTaskId, taskId)
                        .orderByDesc(AgentAsset::getCreatedAt)
                        .last("LIMIT 1"));
        if (records == null || records.isEmpty()) return;
        AgentAsset record = records.getFirst();
        if (isDeleted(record)) return;
        apply(record, update);
        assetMapper.updateById(record);
        assetOutputOutboxService.enqueue(record);
    }

    /**
     * 用户删除素材后，仍在飞行中的生成任务会带着新结果回来覆写这一行。
     * 软删除必须对异步写回是终态，否则删掉的图会带着新 URL 重新出现在会话资产里。
     */
    static boolean isDeleted(AgentAsset record) {
        return record != null && AgentAsset.STATUS_DELETED.equals(record.getStatus());
    }

    private void apply(AgentAsset record, GenerationRecordUpdate update) {
        if (update.status() != null && !update.status().isBlank()) record.setStatus(update.status());
        if (update.url() != null && !update.url().isBlank()) record.setUrl(update.url());
        if (!update.resultUrls().isEmpty()) record.setResultUrls(toJson(update.resultUrls()));
        if (update.error() != null && !update.error().isBlank()) record.setError(update.error());
        if (update.durationMs() != null) {
            record.setDurationMs(update.durationMs());
        } else if (record.getCreatedAt() != null) {
            record.setDurationMs(Duration.between(record.getCreatedAt(), OffsetDateTime.now()).toMillis());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("生成记录 JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
