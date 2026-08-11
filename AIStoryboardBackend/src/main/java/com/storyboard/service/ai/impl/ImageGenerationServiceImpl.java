package com.storyboard.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.FileStorageService;
import com.storyboard.service.ai.AiConfigProperties;
import com.storyboard.service.ai.ImageGenerationService;
import com.storyboard.service.ai.MultipartBuilder;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
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
 * 图片生成服务实现 —— 负责调用 Laozhang API 进行生图/改图。
 */
@Service
@RequiredArgsConstructor
public class ImageGenerationServiceImpl implements ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationServiceImpl.class);

    /**
     * 合法生图尺寸白名单（OpenAI gpt-image 系列，Laozhang 网关仅认这些）。
     * Dify 工作流的 LLM 可能输出 DALL-E 3 尺寸（1792x1024 / 1024x1792）或
     * "2K"/"4K" 等变体，上游返回 400 "不合法的size"（实测 2026-08-04 复现），
     * 故白名单之外的尺寸一律降级为默认值，保证请求必达。
     */
    private static final Set<String> VALID_IMAGE_SIZES = Set.of("1024x1024", "1536x1024", "1024x1536");

    private final AiConfigProperties config;
    private final SceneMapper sceneMapper;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 生图走 Spring AI ImageModel（自动装配的 OpenAiImageModel，spring.ai.openai.* 指向网关，文生图 /v1/images/generations） */
    private final ImageModel imageModel;
    /** edits 图改图仍用 HttpClient 直连（multipart octet-stream workaround，Spring AI 无对应适配） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 生成/编辑图片入口方法（旧签名，n 默认 1；Dify 工作流等调用方使用）。
     */
    @Override
    public String generateImage(String sceneId, String prompt, String model,
                                 String size, String quality, String aspectRatio,
                                 List<String> referenceImages,
                                 String mode, String generatedImageUrl) {
        return generateImage(sceneId, prompt, model, size, quality, aspectRatio,
                referenceImages, mode, generatedImageUrl, null);
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
     * @param n               生成数量（null 或 <=0 时默认 1）
     */
    @Override
    public String generateImage(String sceneId, String prompt, String model,
                                 String size, String quality, String aspectRatio,
                                 List<String> referenceImages,
                                 String mode, String generatedImageUrl, Integer n) {
        // sceneId 可为空：为空时不读写 scene 表（agent_assets 模式）
        Scene scene = sceneId != null ? sceneMapper.selectById(sceneId) : null;
        if (sceneId != null && scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

        if (prompt == null || prompt.isBlank()) {
            throw new RuntimeException("图片生成 prompt 不能为空（Dify 变量可能未正确设置）");
        }

        if (scene != null) {
            scene.setImageStatus("generating");
            sceneMapper.updateById(scene);
        }

        try {
            String result;
            String localPath;
            boolean hasReferenceImages = referenceImages != null && !referenceImages.isEmpty();
            // 模型选择：显式传入 > 分支默认——图改图/编辑分支用 defaultImageEditModel（环境变量可配），纯文生图用 defaultImageModel
            String effectiveModel = model != null ? model
                    : (hasReferenceImages || "edit".equals(mode))
                            ? config.getDefaultImageEditModel()
                            : config.getDefaultImageModel();

            // 有参考图或显式 edit 模式 → /v1/images/edits multipart 接口（经网关）
            if (hasReferenceImages || "edit".equals(mode)) {
                result = callImageEdit(effectiveModel, prompt, referenceImages, generatedImageUrl);
                localPath = fileStorageService.saveImageFromBase64(result);

            // 纯文生图：/v1/images/generations JSON 接口（统一走网关，Gemini 模型由网关转原生格式）
            } else {
                result = callOpenAIImage(effectiveModel, prompt, size, quality, aspectRatio, n);
                if (result.startsWith("http://") || result.startsWith("https://")) {
                    localPath = fileStorageService.saveImage(result);
                } else {
                    localPath = fileStorageService.saveImageFromBase64(result);
                }
            }

            if (scene != null) {
                scene.setImageUrl(localPath);
                scene.setImageStatus("completed");
                sceneMapper.updateById(scene);
            }
            return localPath;
        } catch (Exception e) {
            if (scene != null) {
                scene.setImageStatus("failed");
                sceneMapper.updateById(scene);
            }
            throw new RuntimeException("AI 图片生成失败: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  纯文生图：/v1/images/generations JSON 接口
    //  注意：gpt-image-2 的 generations 接口不支持 reference_images
    // ═══════════════════════════════════════════════════════════

    /**
     * 图片请求带超时重试：Laozhang 偶发响应 >120s（实测 2026-08-06 多次出现），
     * 直接失败体验差；超时后重建请求重试 1 次（JDK HttpClient 的 HttpRequest 发送后
     * 不可复用，重试必须由 factory 重建）。仅 HttpTimeoutException 触发重试，
     * 上游业务错误（4xx/5xx）不重试——重试无意义且会放大费用。
     */
    private HttpResponse<String> sendImageWithRetry(java.util.function.Supplier<HttpRequest> requestFactory)
            throws Exception {
        try {
            return httpClient.send(requestFactory.get(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("图片请求超时({}), 重建请求重试 1 次", e.getMessage());
            return httpClient.send(requestFactory.get(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * 纯文生图：Spring AI ImageModel 调 /v1/images/generations（统一走网关，模型→渠道路由与密钥选择下沉网关）。
     * 超时 180s + 重试 1 次对齐原 HttpClient 语义（OpenAI SDK 重试覆盖超时/连接错误/5xx）。
     */
    private String callOpenAIImage(String model, String prompt, String size, String quality,
                                    String aspectRatio, Integer n) {
        OpenAiImageOptions.Builder builder = OpenAiImageOptions.builder()
                .model(model)
                // size 白名单校验：非法值（DALL-E 3 尺寸/2K/4K 等）降级为默认 1024x1024，
                // 避免上游返回 400 "不合法的size"
                .size(normalizeImageSize(size, config.getDefaultImageSize()))
                // 生成数量：请求显式传入且 >0 时透传，否则默认 1
                .n((n != null && n > 0) ? n : 1)
                // 超时 180s + 重试 1 次（Laozhang 偶发响应 >120s，实测多次出现；重试大概率成功）
                .timeout(Duration.ofSeconds(180))
                .maxRetries(1);
        if (quality != null && !quality.isEmpty()) {
            builder.quality(quality);
        }

        ImageResponse response = imageModel.call(new ImagePrompt(prompt, builder.build()));
        // b64_json 优先，url 兜底（与原实现一致）
        Image image = Objects.requireNonNull(response.getResult()).getOutput();
        String result = image.getB64Json();
        if (result == null || result.isEmpty()) {
            result = image.getUrl();
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    //  图改图：/v1/images/edits multipart 接口
    // ═══════════════════════════════════════════════════════════

    /**
     * 调用 /v1/images/edits 接口进行图改图。
     * <p>
     * 源图获取优先级：
     * 1. generatedImageUrl — 当前已生成的图片路径（完善图片场景）
     * 2. referenceImages[0] — 第一张上传的参考图 base64（参考图生图场景）
     * <p>
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
            String base64Data = referenceImages.getFirst();
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

        // 3. 发送请求（multipart body 一次性构建；超时 180s + 重试 1 次）
        byte[] bodyBytes = mp.build();
        HttpResponse<String> resp = sendImageWithRetry(() -> HttpRequest.newBuilder()
            .uri(URI.create(config.getGatewayBaseUrl() + "/v1/images/edits"))   // 改：走网关
            // 用 octet-stream 发送 multipart 字节流：@RequestBody byte[] 收 multipart 会被
            // Spring 解析器消费 body（实测 500），网关侧从 body 提取 boundary 重建转发头
            .header("Content-Type", "application/octet-stream")
            .header("Authorization", "Bearer " + config.getGatewayApiKey())      // 改：网关业务 Key
            .timeout(Duration.ofSeconds(180))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
            .build());
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

    /**
     * 生图尺寸白名单归一化（包内静态，便于直接单测）：
     * 1. 传入值本身在白名单 → 原样返回
     * 2. 传入值是"多选列表"（LLM 常把模板备选输出成一串，实测 Dify 工作流传出
     *    "1024x1024 / 1536x1024 / 1024x1536"）→ 按 | / 逗号（含中文）等分隔符
     *    拆分，取第一个白名单内的值
     * 3. 拆不出合法值（2K/4K/DALL-E 3 尺寸 1792x1024 等）→ 降级 fallback
     * 避免上游返回 400/500 "不合法的size"。
     */
    static String normalizeImageSize(String size, String fallback) {
        if (size == null || size.isBlank()) return fallback;
        String trimmed = size.trim();
        if (VALID_IMAGE_SIZES.contains(trimmed)) return trimmed;
        // 多选列表：拆分后取第一个白名单值
        for (String token : trimmed.split("[|/,，、;；\\s]+")) {
            String t = token.trim();
            if (VALID_IMAGE_SIZES.contains(t)) {
                log.warn("图片生成 size={} 为多选列表, 取第一个合法值 {}", size, t);
                return t;
            }
        }
        log.warn("图片生成 size={} 上游不支持, 降级为 {}", size, fallback);
        return fallback;
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

}
