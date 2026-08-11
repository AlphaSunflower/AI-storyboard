package com.storyboard.service.agent;

import com.storyboard.dto.request.DifyGenerateImageRequest;
import com.storyboard.dto.request.DifyGenerateScriptRequest;
import com.storyboard.dto.request.DifyGenerateVideoRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Dify Agent 专用 API 服务接口（/api/ai/dify/** 端点业务逻辑）。
 *
 * 认证方式：X-Dify-Key header（由 DifyApiKeyFilter 校验），本服务不涉及。
 * 路径 /api/ai/dify/** 不在 JWT 认证范围内，由独立的 API Key filter 保护。
 *
 * 实现见 {@link com.storyboard.service.agent.impl.DifyAgentServiceImpl}。
 */
public interface DifyAgentService {

    /**
     * Dify Agent 分镜脚本写入。
     * 接收 Agent 生成的 JSON，批量创建 Scene 记录（含 projectId 防御校验）。
     */
    Map<String, Object> generateScript(DifyGenerateScriptRequest request);

    /**
     * Dify Agent 图片生成（JSON 模式，代理 Laozhang API）。
     * 生成后下载到本地，返回访问 URL。
     *
     * mode 参数控制生图模式：
     * - "edit"  → 图改图：调用 /v1/images/edits multipart 接口，源图取 generatedImageUrl 或 referenceImageUrls[0]
     * - 其他/null → 图生图：调用 /v1/images/generations JSON 接口
     */
    Map<String, String> generateImage(DifyGenerateImageRequest request);

    /**
     * Dify Agent 图片生成 — multipart 模式（直接传文件）。
     * Dify HTTP Request 节点选 multipart/form-data，File 变量直接作为文件字段发送，
     * 无需 Code 节点转 base64。
     *
     * 适用于图改图（mode="edit"）和图生图（不传 mode）场景。
     */
    Map<String, String> generateImageMultipart(String projectId, String prompt, String sceneId,
                                               String model, String size, String quality, String mode,
                                               String generatedImageUrl, String conversationId,
                                               String picUrl, List<MultipartFile> images);

    /**
     * Dify Agent 视频生成（异步模式）。
     * 创建视频任务后立即返回 taskId，Dify 通过 GET /status 轮询结果。
     * 避免同步阻塞导致 Squid/Docker 代理超时（视频生成需 2-5 分钟）。
     */
    Map<String, String> generateVideo(DifyGenerateVideoRequest request);

    /**
     * Dify Agent 查询视频任务状态。
     * 轮询此接口直到 status 为 completed 或 failed。
     */
    Map<String, String> pollVideoStatus(String taskId);
}
