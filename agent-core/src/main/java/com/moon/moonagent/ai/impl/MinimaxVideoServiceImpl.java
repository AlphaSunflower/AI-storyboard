package com.moon.moonagent.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.MinimaxVideoService;
import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.ai.SceneView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.storyboard.common.BusinessException;

/**
 * MiniMax 视频生成服务实现 —— 网关通道（协议转换已下沉 LLM 网关）。
 * <p>
 * 轮询/下载/归属更新复用 {@link VideoPollExecutor}；本服务只负责创建任务的请求体组装。
 */
@Service
@RequiredArgsConstructor
public class MinimaxVideoServiceImpl implements MinimaxVideoService {

    private static final Logger log = LoggerFactory.getLogger(MinimaxVideoServiceImpl.class);

    private final AiConfigProperties config;
    private final GatewayModelService gatewayModelService;
    private final SceneGenerationBridge sceneBridge;
    private final VideoPollExecutor videoPollExecutor;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 创建视频生成任务（统一走网关 POST /v1/videos）。
     * 响应解析：网关已归一为 {vendor, taskId}，本层只认 taskId 落库（scene.videoTaskId 逻辑不变）。
     */
    @Override
    public String createVideoTask(String sceneId, String prompt, String alias,
                                  String resolution, String size, String aspectRatio,
                                  Integer duration, String negativePrompt, Long seed,
                                  List<String> referenceImages, String generatedImageUrl) {
        SceneView scene = sceneId != null ? sceneBridge.getScene(sceneId) : null;
        if (sceneId != null && scene == null) throw new BusinessException(40401, "资源不存在: " + sceneId);
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(40001, "视频生成 prompt 不能为空");
        }

        String actualModel = alias != null
                ? config.getVideoModelAliasMap().getOrDefault(alias, alias)
                : gatewayModelService.getDefaultVideoModel();  // 默认模型：网关 model_params.is_default（MiniMax 直连自家，非 laozhang）

        try {
            // 统一 OpenAI 风格 JSON 请求体（模型→渠道路由、协议转换已下沉网关）
            Map<String, Object> body = new HashMap<>();
            body.put("model", actualModel);
            body.put("prompt", prompt);
            // 未显式传值的参数不下发，由网关兜底默认（768P/8s/16:9/1280x720，与旧 config 默认一致）
            if (size != null) body.put("size", size);
            if (resolution != null) body.put("resolution", resolution);
            if (aspectRatio != null) body.put("aspectRatio", aspectRatio);
            if (duration != null) body.put("duration", duration);
            if (negativePrompt != null && !negativePrompt.isEmpty()) {
                body.put("negativePrompt", negativePrompt);
            }
            if (seed != null) {
                body.put("seed", seed);
            }

            // 图生视频 imageUrl：优先已生成图片（本地文件 → data URI 内联——图片在业务 uploads 目录，网关无权限访问），其次参考图第一张
            if (generatedImageUrl != null && !generatedImageUrl.isEmpty()) {
                String filename = extractFilename(generatedImageUrl);
                byte[] bytes = fileStorageService.readImage(filename);
                if (bytes != null) {
                    String mime = FileStorageService.contentType(filename);
                    body.put("imageUrl", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
                } else {
                    log.warn("Reference image not found: {}", filename);
                }
            } else if (referenceImages != null && !referenceImages.isEmpty()) {
                body.put("imageUrl", normalizeImageUrl(referenceImages.get(0)));
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getGatewayBaseUrl() + "/v1/videos"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getGatewayApiKey())
                .timeout(Duration.ofSeconds(config.getVideoRequestTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new BusinessException(50201, "Video API returned " + resp.statusCode() + ": " + resp.body());
            }
            // 网关已按 vendor 归一响应（{vendor, taskId, status?}），本层只认 taskId，不再猜字段名
            JsonNode root = objectMapper.readTree(resp.body());
            String taskId = root.path("taskId").asText("");
            if (taskId.isBlank()) {
                throw new BusinessException(50201, "视频创建响应缺少 taskId: " + resp.body());
            }

            if (scene != null) {
                scene.videoTaskId = taskId;
                scene.videoStatus = "generating";
                sceneBridge.updateScene(scene);
            }
            log.info("视频任务已创建(网关): taskId={}, model={}, resolution={}, duration={}",
                    taskId, actualModel, resolution, duration);
            return taskId;
        } catch (Exception e) {
            if (scene != null) {
                scene.videoStatus = "failed";
                sceneBridge.updateScene(scene);
            }
            throw new BusinessException(50201, "AI 视频生成失败: " + e.getMessage(), e);
        }
    }

    /** 轮询视频任务状态（复用公共执行器）：succeeded→completed / failed→failed / processing→processing */
    @Override
    public Map<String, String> pollVideoTask(String taskId) {
        return videoPollExecutor.pollVideoTask(taskId);
    }

    private String extractFilename(String urlPath) {
        int idx = urlPath.lastIndexOf('/');
        return idx >= 0 ? urlPath.substring(idx + 1) : urlPath;
    }

    /**
     * 参考图 data URI 归一化：已是 data URI 原样返回；裸 base64 补齐前缀
     * （协议转换已下沉网关，业务侧只需保证 imageUrl 是合法 data URI）。
     */
    private String normalizeImageUrl(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        if (base64.startsWith("data:")) return base64;
        if (base64.contains(",") && base64.contains("base64")) {
            return base64.substring(base64.indexOf("data:"));
        }
        return "data:image/png;base64," + base64;
    }
}
