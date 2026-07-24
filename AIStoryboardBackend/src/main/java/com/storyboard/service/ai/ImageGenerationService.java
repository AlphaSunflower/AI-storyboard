package com.storyboard.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ImageGenerationService {

    private final AiConfigProperties config;
    private final SceneMapper sceneMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ImageGenerationService(AiConfigProperties config, SceneMapper sceneMapper) {
        this.config = config;
        this.sceneMapper = sceneMapper;
    }

    public String generateImage(String sceneId, String prompt, String model,
                                 String size, String aspectRatio,
                                 List<String> referenceImages) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

        String effectiveModel = model != null ? model : config.getDefaultImageModel();

        try {
            String imageUrl;
            if ("gemini-3-pro-image-preview".equals(effectiveModel)) {
                imageUrl = callGeminiImage(prompt, aspectRatio, referenceImages);
            } else {
                imageUrl = callOpenAIImage(effectiveModel, prompt, size, aspectRatio, referenceImages);
            }

            // 更新 scene
            scene.setImageUrl(imageUrl);
            scene.setImageStatus("completed");
            sceneMapper.updateById(scene);

            return imageUrl;
        } catch (Exception e) {
            scene.setImageStatus("failed");
            sceneMapper.updateById(scene);
            throw new RuntimeException("AI 图片生成失败: " + e.getMessage(), e);
        }
    }

    private String callOpenAIImage(String model, String prompt, String size,
                                    String aspectRatio, List<String> referenceImages) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size != null ? size : "2K");
        if (referenceImages != null && !referenceImages.isEmpty()) {
            body.put("reference_images", referenceImages);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrlOpenai() + "/images/generations"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + ("gpt-image-2-official".equals(model) ? config.getSora2OfficialApiKey() : config.getApiKey()))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Image API returned " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        return root.path("data").get(0).path("url").asText();
    }

    private String callGeminiImage(String prompt, String aspectRatio,
                                    List<String> referenceImages) throws Exception {
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> part = new HashMap<>();
        part.put("text", prompt);
        body.put("contents", new Object[]{Map.of("parts", new Object[]{part})});
        body.put("generationConfig", Map.of("aspectRatio", aspectRatio != null ? aspectRatio : "16:9"));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrlGemini()))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", config.getApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Gemini API returned " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        // 提取图片 URL（根据 Gemini 响应格式调整）
        return root.path("candidates").get(0).path("content").path("parts").get(0).path("inlineData").path("data").asText();
    }
}
