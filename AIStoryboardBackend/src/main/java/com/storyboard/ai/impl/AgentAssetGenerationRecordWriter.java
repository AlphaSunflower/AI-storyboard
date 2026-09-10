package com.storyboard.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.common.generation.GenerationRecord;
import com.storyboard.common.generation.GenerationRecordUpdate;
import com.storyboard.common.generation.GenerationRecordWriter;
import com.storyboard.entity.AgentAsset;
import com.storyboard.mapper.AgentAssetMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/** AI 分镜服务的生成记录适配器。 */
@Component
@RequiredArgsConstructor
public class AgentAssetGenerationRecordWriter implements GenerationRecordWriter {

    private static final Logger log = LoggerFactory.getLogger(AgentAssetGenerationRecordWriter.class);

    private final AgentAssetMapper assetMapper;
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
        return record.getId();
    }

    @Override
    public void update(String recordId, GenerationRecordUpdate update) {
        if (recordId == null || recordId.isBlank()) return;
        AgentAsset record = assetMapper.selectById(recordId);
        if (record == null) return;
        apply(record, update);
        assetMapper.updateById(record);
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
        List<AgentAsset> records = assetMapper.selectList(
                new LambdaQueryWrapper<AgentAsset>()
                        .eq(AgentAsset::getTaskId, taskId)
                        .orderByDesc(AgentAsset::getCreatedAt)
                        .last("LIMIT 1"));
        if (records == null || records.isEmpty()) return;
        AgentAsset record = records.getFirst();
        apply(record, update);
        assetMapper.updateById(record);
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
