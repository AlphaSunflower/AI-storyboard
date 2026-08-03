package com.storyboard.controller;

import com.storyboard.dto.request.DifyGenerateImageRequest;
import com.storyboard.dto.request.DifyGenerateScriptRequest;
import com.storyboard.dto.request.DifyGenerateVideoRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.ai.ImageGenerationService;
import com.storyboard.service.ai.VideoGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dify Agent 专用 API 端点。
 * 认证方式：X-Dify-Key header（由 DifyApiKeyFilter 校验）。
 *
 * 路径 /api/ai/dify/** 不在 JWT 认证范围内，由独立的 API Key filter 保护。
 */
@RestController
@RequestMapping("/api/ai/dify")
public class DifyAgentController {

    private static final Logger log = LoggerFactory.getLogger(DifyAgentController.class);

    /** 后端从 Dify 容器内部可访问的基础 URL */
    private static final String BACKEND_BASE_URL = "http://host.docker.internal:8082";

    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final SceneMapper sceneMapper;

    public DifyAgentController(ImageGenerationService imageService,
                                VideoGenerationService videoService,
                                SceneMapper sceneMapper) {
        this.imageService = imageService;
        this.videoService = videoService;
        this.sceneMapper = sceneMapper;
    }

    /**
     * Dify Agent 分镜脚本写入。
     * 接收 Agent 生成的 JSON，批量创建 Scene 记录。
     */
    @PostMapping("/generate-script")
    @Transactional
    public ApiResponse<Map<String, Object>> generateScript(
            @RequestBody DifyGenerateScriptRequest request) {
        if (request.scenes() == null || request.scenes().isEmpty()) {
            return ApiResponse.ok(Map.of("projectId", request.projectId(), "sceneCount", 0));
        }
        int count = 0;
        for (var item : request.scenes()) {
            Scene scene = new Scene();
            scene.setProjectId(request.projectId());
            scene.setSceneNumber(item.sceneNumber());
            scene.setScriptContent(item.scriptContent());
            scene.setImagePrompt(item.imagePrompt());
            scene.setVideoPrompt(item.videoPrompt());
            scene.setNegativePrompt(item.negativePrompt());
            scene.setCameraMovement(item.cameraMovement());
            scene.setShotType(item.shotType());
            scene.setSoundDesign(item.soundDesign());
            sceneMapper.insert(scene);
            count++;
        }
        log.info("Dify Agent 写入 {} 个分镜到项目 {}", count, request.projectId());
        return ApiResponse.ok(Map.of("projectId", request.projectId(), "sceneCount", count));
    }

    /**
     * Dify Agent 图片生成（代理 Laozhang API）。
     * 生成后下载到本地，返回访问 URL。
     *
     * mode 参数控制生图模式：
     * - "edit"  → 图改图：调用 /v1/images/edits multipart 接口，源图取 generatedImageUrl 或 referenceImageUrls[0]
     * - 其他/null → 图生图：调用 /v1/images/generations JSON 接口
     */
    @PostMapping(value = "/generate-image", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, String>> generateImage(
            @RequestBody DifyGenerateImageRequest request) {
        // sceneId 优先：传了就用真实分镜记录，没传创建临时 Scene（兼容旧行为）
        String effectiveSceneId;
        if (request.sceneId() != null && !request.sceneId().isBlank()) {
            effectiveSceneId = request.sceneId();
            log.info("Dify Agent 使用真实分镜 sceneId={} 生成图片, mode={}", effectiveSceneId, request.mode());
        } else {
            effectiveSceneId = UUID.randomUUID().toString();
            Scene tempScene = new Scene();
            tempScene.setId(effectiveSceneId);
            tempScene.setProjectId(request.projectId());
            tempScene.setSceneNumber(0);
            sceneMapper.insert(tempScene);
        }

        // 确定生图模式：显式传 "edit" 走图改图，否则走图生图
        String mode = "edit".equals(request.mode()) ? "edit" : null;

        String imageUrl = imageService.generateImage(
            effectiveSceneId,
            sanitize(request.prompt()), sanitize(request.model()),
            sanitize(request.size()), sanitize(request.quality()), null,
            request.referenceImageUrls(),
            mode,
            sanitize(request.generatedImageUrl())
        );
        return ApiResponse.ok(Map.of(
            "imageUrl", BACKEND_BASE_URL + imageUrl,
            "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
        ));
    }

    /**
     * Dify Agent 视频生成（异步模式）。
     * 创建 Laozhang 视频任务后立即返回 taskId，Dify 通过 GET /status 轮询结果。
     * 避免同步阻塞导致 Squid/Docker 代理超时（视频生成需 2-5 分钟）。
     */
    @PostMapping("/generate-video")
    public ApiResponse<Map<String, String>> generateVideo(
            @RequestBody DifyGenerateVideoRequest request) {
        log.info("Dify Agent 创建视频任务: projectId={}", request.projectId());

        // duration 是 String 类型（Dify 变量引用可能是未解析的字符串）
        Integer duration = null;
        if (request.duration() != null && !request.duration().isBlank()) {
            try {
                duration = Integer.parseInt(request.duration());
            } catch (NumberFormatException e) {
                log.warn("Dify Agent 视频 duration 值非法({}), 将使用 service 默认值", request.duration());
            }
        }
        if (duration == null || duration <= 0) {
            log.info("Dify Agent 视频 duration 未设置或无效, 将使用 service 默认值");
        }

        // sceneId 优先：传了就用真实分镜记录，没传创建临时 Scene
        String effectiveSceneId;
        if (request.sceneId() != null && !request.sceneId().isBlank()) {
            effectiveSceneId = request.sceneId();
            log.info("Dify Agent 使用真实分镜 sceneId={} 生成视频", effectiveSceneId);
        } else {
            effectiveSceneId = UUID.randomUUID().toString();
            Scene tempScene = new Scene();
            tempScene.setId(effectiveSceneId);
            tempScene.setProjectId(request.projectId());
            tempScene.setSceneNumber(0);
            sceneMapper.insert(tempScene);
        }

        // 创建视频任务，立即返回 taskId（不阻塞等待）
        String taskId = videoService.createVideoTask(
            effectiveSceneId,
            sanitize(request.prompt()), sanitize(request.model()),
            sanitize(request.resolution()), sanitize(request.size()), sanitize(request.aspectRatio()),
            duration, sanitize(request.negativePrompt()), null,
            request.referenceImageUrls(), null
        );

        log.info("Dify Agent 视频任务已创建: taskId={}, sceneId={}", taskId, effectiveSceneId);
        return ApiResponse.ok(Map.of(
            "taskId", taskId,
            "sceneId", effectiveSceneId,
            "status", "queued"
        ));
    }

    /**
     * Dify Agent 查询视频任务状态。
     * 轮询此接口直到 status 为 completed 或 failed。
     */
    @GetMapping("/generate-video/status")
    public ApiResponse<Map<String, String>> pollVideoStatus(@RequestParam String taskId) {
        Map<String, String> result = videoService.pollVideoTask(taskId);
        String status = result.get("status");
        if ("completed".equals(status)) {
            String videoUrl = result.getOrDefault("videoUrl", "");
            return ApiResponse.ok(Map.of(
                "taskId", taskId,
                "status", "completed",
                "videoUrl", videoUrl.startsWith("http") ? videoUrl : BACKEND_BASE_URL + videoUrl
            ));
        }
        if ("failed".equals(status)) {
            return ApiResponse.ok(Map.of(
                "taskId", taskId,
                "status", "failed",
                "error", result.getOrDefault("error", "未知错误")
            ));
        }
        // queued 或 in_progress
        return ApiResponse.ok(Map.of(
            "taskId", taskId,
            "status", status,
            "progress", result.getOrDefault("progress", "0")
        ));
    }

    /**
     * Dify Agent 图片生成 — multipart 模式（直接传文件）。
     * Dify HTTP Request 节点选 multipart/form-data，File 变量直接作为文件字段发送，
     * 无需 Code 节点转 base64。
     *
     * 适用于图改图（mode="edit"）和图生图（不传 mode）场景。
     */
    @PostMapping(value = "/generate-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> generateImageMultipart(
            @RequestParam String projectId,
            @RequestParam String prompt,
            @RequestParam(required = false) String sceneId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String quality,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String generatedImageUrl,
            @RequestPart(required = false) List<MultipartFile> images) {

        String effectiveSceneId;
        if (sceneId != null && !sceneId.isBlank()) {
            effectiveSceneId = sceneId;
        } else {
            effectiveSceneId = UUID.randomUUID().toString();
            Scene tempScene = new Scene();
            tempScene.setId(effectiveSceneId);
            tempScene.setProjectId(projectId);
            tempScene.setSceneNumber(0);
            sceneMapper.insert(tempScene);
        }
        log.info("Dify Agent multipart 生成图片 sceneId={}, mode={}, files={}",
                effectiveSceneId, mode, images != null ? images.size() : 0);

        // 文件 → base64 data URL 列表
        List<String> referenceImageUrls = toBase64DataUrls(images);

        String effectiveMode = "edit".equals(mode) ? "edit" : null;

        String imageUrl = imageService.generateImage(
            effectiveSceneId,
            sanitize(prompt), sanitize(model),
            sanitize(size), sanitize(quality), null,
            referenceImageUrls.isEmpty() ? null : referenceImageUrls,
            effectiveMode,
            sanitize(generatedImageUrl)
        );
        return ApiResponse.ok(Map.of(
            "imageUrl", BACKEND_BASE_URL + imageUrl,
            "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
        ));
    }

    /**
     * MultipartFile 列表 → base64 data URL 列表
     */
    private List<String> toBase64DataUrls(List<MultipartFile> files) {
        List<String> result = new ArrayList<>();
        if (files == null) return result;
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            try {
                byte[] bytes = file.getBytes();
                String b64 = Base64.getEncoder().encodeToString(bytes);
                String mime = file.getContentType() != null ? file.getContentType() : "image/png";
                result.add("data:" + mime + ";base64," + b64);
            } catch (Exception e) {
                log.warn("读取上传文件失败: {}", file.getOriginalFilename(), e);
            }
        }
        return result;
    }

    /**
     * 清洗 Dify 传入的字符串值：未解析的 Dify 变量引用（含 structured_output.）
     * 视为无效值，返回 null 让 service 层使用默认值。
     */
    static String sanitize(String value) {
        if (value == null || value.isBlank()) return null;
        // Dify 未解析变量引用：{nodeId}.structured_output.{field} 或纯数字.nodeId
        if (value.contains("structured_output.")) {
            return null;
        }
        return value;
    }
}
