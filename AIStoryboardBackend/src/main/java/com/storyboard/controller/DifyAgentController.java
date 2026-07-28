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
import org.springframework.web.bind.annotation.*;

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

    /** 视频轮询最大次数（60 × 5 秒 = 5 分钟） */
    private static final int VIDEO_POLL_MAX_ATTEMPTS = 60;
    /** 视频轮询间隔（毫秒） */
    private static final long VIDEO_POLL_INTERVAL_MS = 5_000;

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
    public ApiResponse<Map<String, Object>> generateScript(
            @RequestBody DifyGenerateScriptRequest request) {
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
     */
    @PostMapping("/generate-image")
    public ApiResponse<Map<String, String>> generateImage(
            @RequestBody DifyGenerateImageRequest request) {
        // 创建临时 Scene 记录 — ImageGenerationService 需要通过 sceneMapper.selectById 定位 scene
        String tempSceneId = UUID.randomUUID().toString();
        Scene tempScene = new Scene();
        tempScene.setId(tempSceneId);
        tempScene.setProjectId(request.projectId());
        sceneMapper.insert(tempScene);

        String imageUrl = imageService.generateImage(
            tempSceneId, request.prompt(), request.model(),
            request.size(), request.quality(), null,
            request.referenceImageUrls(),
            null, null  // mode=null → generations 接口
        );
        return ApiResponse.ok(Map.of("imageUrl", imageUrl));
    }

    /**
     * Dify Agent 视频生成（代理 Laozhang API + 轮询 + 下载）。
     * 同步等待视频生成完成，Dify 调用方只需一次 HTTP 请求。
     *
     * <p>VideoGenerationService.pollVideoTask 是一次性状态查询（不阻塞），
     * 因此轮询循环在 Controller 层实现：最多 60 次 × 5 秒 = 5 分钟。</p>
     */
    @PostMapping("/generate-video")
    public ApiResponse<Map<String, String>> generateVideo(
            @RequestBody DifyGenerateVideoRequest request) {
        // 创建临时 Scene 记录 — VideoGenerationService 需要通过 sceneMapper.selectById 定位 scene
        String tempSceneId = UUID.randomUUID().toString();
        Scene tempScene = new Scene();
        tempScene.setId(tempSceneId);
        tempScene.setProjectId(request.projectId());
        sceneMapper.insert(tempScene);

        // 创建视频任务
        String taskId = videoService.createVideoTask(
            tempSceneId, request.prompt(), request.model(),
            request.resolution(), request.size(), request.aspectRatio(),
            request.duration(), request.negativePrompt(), null,
            request.referenceImageUrls(), null
        );

        // 轮询等待完成
        for (int i = 0; i < VIDEO_POLL_MAX_ATTEMPTS; i++) {
            Map<String, String> result = videoService.pollVideoTask(taskId);
            String status = result.get("status");
            if ("completed".equals(status)) {
                return ApiResponse.ok(Map.of(
                    "videoUrl", result.getOrDefault("videoUrl", ""),
                    "taskId", taskId
                ));
            }
            if ("failed".equals(status)) {
                return ApiResponse.error(500,
                    "视频生成失败: " + result.getOrDefault("error", "未知错误"));
            }
            try {
                Thread.sleep(VIDEO_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ApiResponse.error(500, "视频生成被中断");
            }
        }
        return ApiResponse.error(500, "视频生成超时（超过 5 分钟）");
    }
}
