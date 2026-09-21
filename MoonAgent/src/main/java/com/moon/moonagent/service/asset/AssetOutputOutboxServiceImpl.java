package com.moon.moonagent.service.asset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AssetOutboxEvent;
import com.moon.moonagent.mapper.AssetOutboxMapper;
import com.storyboard.asset.client.AssetContracts;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssetOutputOutboxServiceImpl implements AssetOutputOutboxService {

    private static final Logger log = LoggerFactory.getLogger(AssetOutputOutboxServiceImpl.class);

    private final AssetOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void enqueue(AgentAsset asset) {
        if (asset == null || asset.getId() == null || asset.getUserId() == null) return;
        if (!List.of("completed", "succeeded").contains(asset.getStatus())) return;
        if (!List.of("image", "video", "audio").contains(asset.getType())) return;

        List<String> urls = resultUrls(asset);
        for (int index = 0; index < urls.size(); index++) {
            try {
                AssetOutboxEvent event = new AssetOutboxEvent();
                event.setEventId(UUID.randomUUID().toString());
                event.setProducer("moon-agent");
                event.setGenerationRecordId(asset.getId());
                event.setResultIndex(index);
                event.setResultAssetId(asset.getId());
                event.setPayloadJson(objectMapper.writeValueAsString(outputEvent(asset, urls.get(index), index)));
                outboxMapper.insertIgnore(event);
            } catch (Exception e) {
                log.warn("生成结果写入项目库 outbox 失败: recordId={}, resultIndex={}, 原因={}",
                        asset.getId(), index, e.getMessage());
            }
        }
    }

    private static AssetContracts.GenerationOutputEvent outputEvent(AgentAsset asset, String url, int index) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "genType", asset.getGenType());
        put(metadata, "sceneId", asset.getSceneId());
        put(metadata, "conversationId", asset.getConversationId());
        return new AssetContracts.GenerationOutputEvent(
                "moon-agent",
                asset.getUserId(),
                asset.getProjectId(),
                asset.getId(),
                index,
                null,
                asset.getId(),
                url,
                asset.getType().toUpperCase(),
                asset.getPrompt(),
                asset.getModel(),
                metadata
        );
    }

    private List<String> resultUrls(AgentAsset asset) {
        if (asset.getResultUrls() != null && !asset.getResultUrls().isBlank()) {
            try {
                return objectMapper.readValue(asset.getResultUrls(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("生成结果 URL 解析失败: recordId={}, 原因={}", asset.getId(), e.getMessage());
            }
        }
        return asset.getUrl() == null || asset.getUrl().isBlank() ? List.of() : List.of(asset.getUrl());
    }

    private static void put(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) metadata.put(key, value);
    }
}
