package com.storyboard.service.agent.impl;

import com.storyboard.dto.request.DifyGenerateImageRequest;
import com.storyboard.dto.request.DifyGenerateScriptRequest;
import com.storyboard.dto.request.DifyGenerateVideoRequest;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.Scene;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.AgentConversationMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.agent.DifyAgentService;
import com.storyboard.service.ai.ImageGenerationService;
import com.storyboard.service.ai.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dify Agent 专用 API 服务实现（/api/ai/dify/** 端点业务逻辑）。
 *
 * 逻辑从 DifyAgentController 原样下沉（企业级分层重构），
 * HTTP 语义 / URL / 请求响应结构 / X-Dify-Key 鉴权机制完全不变。
 */
@Service
@RequiredArgsConstructor
public class DifyAgentServiceImpl implements DifyAgentService {

    private static final Logger log = LoggerFactory.getLogger(DifyAgentServiceImpl.class);

    /** 后端从 Dify 容器内部可访问的基础 URL */
    private static final String BACKEND_BASE_URL = "http://host.docker.internal:8082";

    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final SceneMapper sceneMapper;
    private final AgentAssetMapper agentAssetMapper;
    private final AgentConversationMapper conversationMapper;
    private final ProjectMapper projectMapper;

    /**
     * Dify Agent 分镜脚本写入。
     * 接收 Agent 生成的 JSON，批量创建 Scene 记录。
     */
    @Override
    @Transactional
    public Map<String, Object> generateScript(DifyGenerateScriptRequest request) {
        // projectId 防御校验（Dify 工作流 POST 节点变量绑定断开会渲染成空串，
        // 直接插 scenes 表会外键违例 500；这里明确报业务错误，工作流能拿到可读原因）
        String projectId = sanitize(request.projectId());
        if (projectId == null) {
            log.warn("Dify Agent generate-script 缺少 projectId（工作流 POST 节点变量未绑定），请求拒绝");
            throw new BusinessException(40001, "generate-script 缺少 projectId：请检查 Dify 工作流 POST分镜脚本 节点的 projectId 变量绑定");
        }
        if (projectMapper.selectById(projectId) == null) {
            log.warn("Dify Agent generate-script projectId={} 在 projects 表中不存在，请求拒绝", projectId);
            throw new BusinessException(40401, "项目不存在: " + projectId);
        }
        if (request.scenes() == null || request.scenes().isEmpty()) {
            return Map.of("projectId", projectId, "sceneCount", 0);
        }
        int count = 0;
        for (var item : request.scenes()) {
            Scene scene = new Scene();
            scene.setProjectId(projectId);
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
        log.info("Dify Agent 写入 {} 个分镜到项目 {}", count, projectId);
        return Map.of("projectId", projectId, "sceneCount", count);
    }

    /**
     * Dify Agent 图片生成（代理 Laozhang API）。
     * 生成后下载到本地，返回访问 URL。
     *
     * mode 参数控制生图模式：
     * - "edit"  → 图改图：调用 /v1/images/edits multipart 接口，源图取 generatedImageUrl 或 referenceImageUrls[0]
     * - 其他/null → 图生图：调用 /v1/images/generations JSON 接口
     */
    @Override
    public Map<String, String> generateImage(DifyGenerateImageRequest request) {
        // sceneId 非空 → 写真实分镜；为空 → 写 agent_assets（不再创建临时 scene）
        String effectiveSceneId = (request.sceneId() != null && !request.sceneId().isBlank())
                ? request.sceneId() : null;
        if (effectiveSceneId != null) {
            log.info("Dify Agent 使用真实分镜 sceneId={} 生成图片, mode={}", effectiveSceneId, request.mode());
        }

        // 确定生图模式：显式传 "edit" 走图改图，否则走图生图
        String mode = "edit".equals(request.mode()) ? "edit" : null;

        // picUrl（用户上传图）优先于 generatedImageUrl（完善已有图）
        String effectiveGeneratedImageUrl = (request.picUrl() != null && !request.picUrl().isBlank())
                ? request.picUrl() : sanitize(request.generatedImageUrl());

        String imageUrl = imageService.generateImage(
            effectiveSceneId,
            sanitize(request.prompt()), sanitize(request.model()),
            sanitize(request.size()), sanitize(request.quality()), null,
            request.referenceImageUrls(),
            mode,
            effectiveGeneratedImageUrl
        );

        if (effectiveSceneId == null) {
            AgentAsset asset = writeAgentImageAsset(
                request.conversationId(), request.prompt(), request.model(), imageUrl);
            return Map.of(
                "imageUrl", BACKEND_BASE_URL + imageUrl,
                "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1),
                "assetId", asset.getId()
            );
        }
        return Map.of(
            "imageUrl", BACKEND_BASE_URL + imageUrl,
            "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
        );
    }

    /**
     * Dify Agent 图片生成 — multipart 模式（直接传文件）。
     * Dify HTTP Request 节点选 multipart/form-data，File 变量直接作为文件字段发送，
     * 无需 Code 节点转 base64。
     *
     * 适用于图改图（mode="edit"）和图生图（不传 mode）场景。
     */
    @Override
    public Map<String, String> generateImageMultipart(String projectId, String prompt, String sceneId,
                                                      String model, String size, String quality, String mode,
                                                      String generatedImageUrl, String conversationId,
                                                      String picUrl, List<MultipartFile> images) {
        // projectId 保留以兼容现有 Dify 工作流（方法内未实际使用）

        String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
        log.info("Dify Agent multipart 生成图片 sceneId={}, mode={}, files={}",
                effectiveSceneId, mode, images != null ? images.size() : 0);

        // 文件 → base64 data URL 列表
        List<String> referenceImageUrls = toBase64DataUrls(images);

        String effectiveMode = "edit".equals(mode) ? "edit" : null;

        String effectiveGeneratedImageUrl = (picUrl != null && !picUrl.isBlank())
                ? picUrl : sanitize(generatedImageUrl);

        String imageUrl = imageService.generateImage(
            effectiveSceneId,
            sanitize(prompt), sanitize(model),
            sanitize(size), sanitize(quality), null,
            referenceImageUrls.isEmpty() ? null : referenceImageUrls,
            effectiveMode,
            effectiveGeneratedImageUrl
        );

        if (effectiveSceneId == null) {
            AgentAsset asset = writeAgentImageAsset(conversationId, prompt, model, imageUrl);
            return Map.of(
                "imageUrl", BACKEND_BASE_URL + imageUrl,
                "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1),
                "assetId", asset.getId()
            );
        }
        return Map.of(
            "imageUrl", BACKEND_BASE_URL + imageUrl,
            "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
        );
    }

    /**
     * Dify Agent 视频生成（异步模式）。
     * 创建 Laozhang 视频任务后立即返回 taskId，Dify 通过 GET /status 轮询结果。
     * 避免同步阻塞导致 Squid/Docker 代理超时（视频生成需 2-5 分钟）。
     */
    @Override
    public Map<String, String> generateVideo(DifyGenerateVideoRequest request) {
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

        // sceneId 非空 → 写真实分镜；为空 → 写 agent_assets
        String effectiveSceneId = (request.sceneId() != null && !request.sceneId().isBlank())
                ? request.sceneId() : null;
        if (effectiveSceneId != null) {
            log.info("Dify Agent 使用真实分镜 sceneId={} 生成视频", effectiveSceneId);
        }

        String effectiveGeneratedImageUrl = (request.picUrl() != null && !request.picUrl().isBlank())
                ? request.picUrl() : null;

        // 创建视频任务，立即返回 taskId（不阻塞等待）
        String taskId = videoService.createVideoTask(
            effectiveSceneId,
            sanitize(request.prompt()), sanitize(request.model()),
            sanitize(request.resolution()), sanitize(request.size()), sanitize(request.aspectRatio()),
            duration, sanitize(request.negativePrompt()), null,
            request.referenceImageUrls(), effectiveGeneratedImageUrl
        );

        log.info("Dify Agent 视频任务已创建: taskId={}, sceneId={}", taskId, effectiveSceneId);

        if (effectiveSceneId == null) {
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(sanitizeConversationId(request.conversationId()));
            asset.setType("video");
            asset.setPrompt(sanitize(request.prompt()));
            asset.setModel(sanitize(request.model()));
            asset.setStatus("queued");
            asset.setTaskId(taskId);
            String assetId = null;
            try {
                agentAssetMapper.insert(asset);
                assetId = asset.getId();
                log.info("Agent 视频资产已落库: assetId={}, taskId={}", assetId, taskId);
            } catch (Exception e) {
                // 资产落库失败不应丢弃已创建的 Laozhang 任务（避免白扣费）：
                // 记录错误日志，仍返回 taskId 供 Dify 轮询获取视频结果
                log.error("Agent 视频资产落库失败(不影响任务执行), taskId={}, 原因: {}", taskId, e.getMessage());
            }
            // I4：无 sceneId 分支不再把 assetId 塞进 sceneId 键，避免 Dify 工作流
            // 把 asset id 当 sceneId 回传导致"分镜不存在"
            Map<String, String> resp = new HashMap<>();
            resp.put("taskId", taskId);
            resp.put("status", "queued");
            if (assetId != null) {
                resp.put("assetId", assetId);
            }
            return resp;
        }
        return Map.of(
            "taskId", taskId,
            "sceneId", effectiveSceneId,
            "status", "queued"
        );
    }

    /**
     * Dify Agent 查询视频任务状态。
     * 轮询此接口直到 status 为 completed 或 failed。
     */
    @Override
    public Map<String, String> pollVideoStatus(String taskId) {
        Map<String, String> result = videoService.pollVideoTask(taskId);
        String status = result.get("status");
        if ("completed".equals(status)) {
            String videoUrl = result.getOrDefault("videoUrl", "");
            if (videoUrl == null || videoUrl.isEmpty()) {
                // 防御：上游 completed 但本地下载失败时 videoUrl 可能为 null/空，
                // 直接 startsWith 会 NPE；转 failed 并把下载错误透出给工作流
                String err = result.get("error");
                if (err == null || err.isEmpty()) {
                    err = "视频已生成但下载失败，请重试";
                }
                return Map.of(
                    "taskId", taskId,
                    "status", "failed",
                    "error", err
                );
            }
            return Map.of(
                "taskId", taskId,
                "status", "completed",
                "videoUrl", videoUrl.startsWith("http") ? videoUrl : BACKEND_BASE_URL + videoUrl
            );
        }
        if ("failed".equals(status)) {
            return Map.of(
                "taskId", taskId,
                "status", "failed",
                "error", result.getOrDefault("error", "未知错误")
            );
        }
        // queued 或 in_progress
        return Map.of(
            "taskId", taskId,
            "status", status,
            "progress", result.getOrDefault("progress", "0")
        );
    }

    /**
     * 将生成结果写入 agent_assets（sceneId 为空时调用）。
     * conversationId 为空则创建未归属资产（conversation_id = NULL）。
     */
    private AgentAsset writeAgentImageAsset(String conversationId, String prompt,
                                             String model, String imageUrl) {
        AgentAsset asset = new AgentAsset();
        asset.setConversationId(sanitizeConversationId(conversationId));
        asset.setType("image");
        asset.setUrl(imageUrl);
        asset.setPrompt(sanitize(prompt));
        asset.setModel(sanitize(model));
        asset.setStatus("completed");
        agentAssetMapper.insert(asset);
        log.info("Agent 图片资产已落库: assetId={}, conversationId={}", asset.getId(), asset.getConversationId());
        return asset;
    }

    /**
     * 清洗并校验 Dify 传入的 conversationId（I3）：
     * - 空值/未解析变量引用 → null（未归属资产，照存）；
     * - 非空但 conversations 表中不存在 → 降级为未归属（conversation_id = null 照存），
     *   避免外键违例导致 500。
     */
    private String sanitizeConversationId(String conversationId) {
        String clean = sanitize(conversationId);
        if (clean == null) {
            return null;
        }
        if (conversationMapper.selectById(clean) == null) {
            log.warn("conversationId={} 在 conversations 表中不存在, 资产降级为未归属", clean);
            return null;
        }
        return clean;
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
    public static String sanitize(String value) {
        if (value == null || value.isBlank()) return null;
        // Dify 未解析变量引用：{nodeId}.structured_output.{field} 或纯数字.nodeId
        if (value.contains("structured_output.")) {
            return null;
        }
        return value;
    }
}
