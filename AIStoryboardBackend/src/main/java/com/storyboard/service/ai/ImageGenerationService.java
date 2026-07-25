package com.storyboard.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.FileStorageService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * 图片生成服务 —— 负责调用 Laozhang API 进行生图/改图。
 *
 * 两种模式：
 * 1. "generate"（默认）— 图生图：使用 /v1/images/generations JSON 接口
 *    适用于普通生图、带参考图的生图（reference_images 参数）
 * 2. "edit" — 图改图：使用 /v1/images/edits multipart 接口
 *    适用于完善已有图片（当前生成图为源图）、参考图生图（第一张参考图为源图）
 */
@Service
public class ImageGenerationService {

    private final AiConfigProperties config;
    private final SceneMapper sceneMapper;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public ImageGenerationService(AiConfigProperties config, SceneMapper sceneMapper,
                                   FileStorageService fileStorageService) {
        this.config = config;
        this.sceneMapper = sceneMapper;
        this.fileStorageService = fileStorageService;
    }

    /**
     * 生成/编辑图片入口方法。
     *
     * @param sceneId         分镜 ID
     * @param prompt          生图/改图提示词
     * @param model           AI 模型名
     * @param size            图片尺寸（仅 generations 模式使用）
     * @param aspectRatio     宽高比（Gemini 模式使用）
     * @param referenceImages 参考图列表（base64 data URL 数组）
     * @param mode            "edit" 或 null（null 视为 "generate"）
     * @param generatedImageUrl 当前已生成图片的 URL 路径（完善图片时提供，作为 edits 源图）
     */
    public String generateImage(String sceneId, String prompt, String model,
                                 String size, String quality, String aspectRatio,
                                 List<String> referenceImages,
                                 String mode, String generatedImageUrl) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

        String effectiveModel = model != null ? model : config.getDefaultImageModel();

        scene.setImageStatus("generating");
        sceneMapper.updateById(scene);

        try {
            String result;

            // 图改图模式：使用 /v1/images/edits multipart 接口
            if ("edit".equals(mode)) {
                result = callImageEdit(effectiveModel, prompt, referenceImages, generatedImageUrl);
                String localPath = fileStorageService.saveImageFromBase64(result);
                scene.setImageUrl(localPath);

            // Gemini 原生接口
            } else if (config.getGeminiImageModelSet().contains(effectiveModel)) {
                result = callGeminiImage(prompt, aspectRatio, referenceImages);
                String localPath = fileStorageService.saveImageFromBase64(result);
                scene.setImageUrl(localPath);

            // OpenAI 兼容接口（图生图）
            } else {
                result = callOpenAIImage(effectiveModel, prompt, size, quality, aspectRatio, referenceImages);
                String localPath;
                if (result.startsWith("http://") || result.startsWith("https://")) {
                    localPath = fileStorageService.saveImage(result);
                } else {
                    localPath = fileStorageService.saveImageFromBase64(result);
                }
                scene.setImageUrl(localPath);
            }

            scene.setImageStatus("completed");
            sceneMapper.updateById(scene);
            return scene.getImageUrl();
        } catch (Exception e) {
            scene.setImageStatus("failed");
            sceneMapper.updateById(scene);
            throw new RuntimeException("AI 图片生成失败: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  图生图：OpenAI 兼容 JSON 接口
    // ═══════════════════════════════════════════════════════════

    private String callOpenAIImage(String model, String prompt, String size, String quality,
                                    String aspectRatio, List<String> referenceImages) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size != null ? size : config.getDefaultImageSize());
        if (quality != null && !quality.isEmpty()) {
            body.put("quality", quality);
        }
        if (referenceImages != null && !referenceImages.isEmpty()) {
            body.put("reference_images", referenceImages);
        }

        String apiKey = config.getSora2ModelSet().contains(model)
                ? config.getSora2OfficialApiKey()
                : config.getApiKey();

        String requestBody = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrlOpenai() + config.getEndpointImageGenerations()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Image API returned " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode data = root.path("data").get(0);
        String url = data.path("b64_json").asText();
        if (url == null || url.isEmpty()) {
            url = data.path("url").asText();
        }
        return url;
    }

    // ═══════════════════════════════════════════════════════════
    //  图改图：/v1/images/edits multipart 接口
    // ═══════════════════════════════════════════════════════════

    /**
     * 调用 /v1/images/edits 接口进行图改图。
     *
     * 源图获取优先级：
     * 1. generatedImageUrl — 当前已生成的图片路径（完善图片场景）
     * 2. referenceImages[0] — 第一张上传的参考图 base64（参考图生图场景）
     *
     * 返回 b64_json 字符串（已清洗 data URI 前缀及补齐 base64 padding）。
     */
    private String callImageEdit(String model, String prompt,
                                  List<String> referenceImages,
                                  String generatedImageUrl) throws Exception {
        // 1. 确定源图字节数据
        byte[] imageBytes;
        String imageFilename;

        if (generatedImageUrl != null && !generatedImageUrl.isEmpty()) {
            // 完善图片：从本地存储读取当前生成图
            String filename = extractFilename(generatedImageUrl);
            Path localFile = fileStorageService.resolveImage(filename);
            if (!Files.exists(localFile)) {
                throw new RuntimeException("源图文件不存在: " + localFile);
            }
            imageBytes = Files.readAllBytes(localFile);
            imageFilename = filename;
        } else if (referenceImages != null && !referenceImages.isEmpty()) {
            // 参考图生图：使用第一张参考图（base64 data URL）
            String base64Data = referenceImages.get(0);
            imageBytes = decodeBase64Image(base64Data);
            imageFilename = "reference.png";
        } else {
            throw new RuntimeException("图改图模式下必须提供 generatedImageUrl 或 referenceImages");
        }

        // 2. 构建 multipart/form-data 请求体
        MultipartBuilder mp = new MultipartBuilder()
            .field("model", model)
            .field("prompt", prompt)
            .file("image", imageFilename, guessImageContentType(imageFilename), imageBytes);

        // 3. 发送请求
        String apiKey = config.getApiKey();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrlOpenai() + config.getEndpointImageEdits()))
            .header("Content-Type", "multipart/form-data; boundary=" + mp.boundary())
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofByteArray(mp.build()))
            .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Image Edit API returned " + resp.statusCode() + ": " + resp.body());
        }

        // 4. 解析响应，提取 b64_json（需补齐 padding）
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode data = root.path("data").get(0);
        String b64Json = data.path("b64_json").asText();
        if (b64Json == null || b64Json.isEmpty()) {
            b64Json = data.path("url").asText();
        }
        if (b64Json == null || b64Json.isEmpty()) {
            throw new RuntimeException("Image Edit API 返回结果为空");
        }

        return cleanBase64(b64Json);
    }

    // ═══════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════

    /** 从 URL 路径中提取文件名（如 /api/files/images/abc.png → abc.png） */
    private String extractFilename(String urlPath) {
        int idx = urlPath.lastIndexOf('/');
        return idx >= 0 ? urlPath.substring(idx + 1) : urlPath;
    }

    /** 解码 base64 data URL 为字节数组 */
    private byte[] decodeBase64Image(String base64Data) {
        String clean = base64Data;
        // 去掉 data URI 前缀
        if (clean.contains(",") && clean.contains("base64")) {
            clean = clean.substring(clean.indexOf(",") + 1);
        }
        return Base64.getDecoder().decode(clean);
    }

    /**
     * 清洗 base64 字符串：
     * 1. 去掉可能的 data URI 前缀
     * 2. 补齐缺失的 base64 padding
     */
    static String cleanBase64(String value) {
        if (value == null) return "";
        // 去掉 data URI 前缀
        if (value.startsWith("data:")) {
            int comma = value.indexOf(",");
            if (comma > 0) value = value.substring(comma + 1);
        }
        // 补齐 base64 padding（base64 长度必须是 4 的倍数）
        int remainder = value.length() % 4;
        if (remainder > 0) {
            value += "=".repeat(4 - remainder);
        }
        return value;
    }

    /** 根据文件名推测图片 MIME 类型 */
    private String guessImageContentType(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "image/png";
        };
    }

    // ═══════════════════════════════════════════════════════════
    //  Gemini 原生接口（保留不变）
    // ═══════════════════════════════════════════════════════════

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
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Gemini API returned " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        return root.path("candidates").get(0).path("content").path("parts").get(0).path("inlineData").path("data").asText();
    }
}
