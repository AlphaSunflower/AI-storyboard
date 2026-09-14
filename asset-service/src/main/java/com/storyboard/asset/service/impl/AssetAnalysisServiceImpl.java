package com.storyboard.asset.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.config.AssetProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetAnalysisService;
import com.storyboard.asset.storage.ObjectStorage;
import com.storyboard.asset.storage.StoredObject;
import com.storyboard.common.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class AssetAnalysisServiceImpl implements AssetAnalysisService {

    private static final long MAX_ANALYSIS_BYTES = 20L * 1024 * 1024;

    private final LibraryRepository repository;
    private final ObjectStorage storage;
    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public AssetAnalysisServiceImpl(
            LibraryRepository repository,
            ObjectStorage storage,
            AssetProperties properties
    ) {
        this.repository = repository;
        this.storage = storage;
        this.restClient = RestClient.builder()
                .baseUrl(properties.gatewayBaseUrl() == null ? "http://localhost:8083" : properties.gatewayBaseUrl())
                .build();
        this.apiKey = properties.gatewayApiKey() == null ? "" : properties.gatewayApiKey();
    }

    @Override
    public void analyze(LibraryRepository.AnalysisTask task) {
        MediaRef media = repository.listMedia(task.assetId()).stream()
                .filter(item -> item.type() == com.storyboard.asset.client.AssetContracts.MediaType.IMAGE)
                .findFirst()
                .orElseThrow(() -> new BusinessException(40001, "资产没有可分析图片"));
        if (media.sizeBytes() > MAX_ANALYSIS_BYTES) {
            throw new BusinessException(40001, "图片超过自动分析上限 20MB");
        }
        String dataUri;
        try (StoredObject object = storage.get(media.objectKey(), null)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(media.sizeBytes(), MAX_ANALYSIS_BYTES));
            object.stream().transferTo(output);
            dataUri = "data:" + media.mimeType() + ";base64,"
                    + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception e) {
            throw new BusinessException(50201, "读取待分析图片失败", e);
        }

        Map<String, Object> payload = Map.of(
                "model", "gemini-3-flash-preview",
                "temperature", 0,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", """
                                        请描述这张素材，并给出最多8个中文标签。
                                        只返回 JSON：{"description":"...","tags":["..."],"constraint":"..."}
                                        constraint 是生成时保持主体一致的简短文字约束。
                                        """),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUri))
                        )
                ))
        );
        JsonNode response = restClient.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
        String content = response == null ? null
                : response.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) throw new BusinessException(50201, "视觉模型返回为空");
        try {
            JsonNode json = mapper.readTree(stripFence(content));
            String description = json.path("description").asText("");
            String constraint = json.path("constraint").asText("");
            List<String> tags = new ArrayList<>();
            for (JsonNode tag : json.path("tags")) {
                if (!tag.asText("").isBlank()) tags.add(tag.asText());
            }
            String storedDescription = constraint.isBlank()
                    ? description
                    : description + "\n文字约束：" + constraint;
            repository.markAiMetadata(task.assetId(), storedDescription, List.copyOf(new LinkedHashSet<>(tags)));
        } catch (Exception e) {
            throw new BusinessException(50201, "视觉模型结构化结果解析失败", e);
        }
    }

    private static String stripFence(String content) {
        String value = content.trim();
        if (value.startsWith("```")) {
            int first = value.indexOf('\n');
            int last = value.lastIndexOf("```");
            if (first >= 0 && last > first) value = value.substring(first + 1, last).trim();
        }
        return value;
    }
}
