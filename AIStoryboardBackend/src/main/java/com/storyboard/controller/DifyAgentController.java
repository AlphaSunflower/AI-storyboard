package com.storyboard.controller;

import com.storyboard.dto.request.DifyGenerateImageRequest;
import com.storyboard.dto.request.DifyGenerateScriptRequest;
import com.storyboard.dto.request.DifyGenerateVideoRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.service.agent.DifyAgentService;
import com.storyboard.service.agent.impl.DifyAgentServiceImpl;
import org.springframework.http.MediaType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Dify Agent 专用 API 端点。
 * 认证方式：X-Dify-Key header（由 DifyApiKeyFilter 校验）。
 *
 * 路径 /api/ai/dify/** 不在 JWT 认证范围内，由独立的 API Key filter 保护。
 *
 * 企业级分层：本控制器只负责收参 → 调 DifyAgentService → ApiResponse 封装，
 * 业务逻辑全部下沉到 DifyAgentServiceImpl（HTTP 语义 / URL / 请求响应结构不变）。
 */
@RestController
@RequestMapping("/api/ai/dify")
@RequiredArgsConstructor
public class DifyAgentController {

    private final DifyAgentService difyAgentService;

    /**
     * Dify Agent 分镜脚本写入。
     * 接收 Agent 生成的 JSON，批量创建 Scene 记录。
     */
    @PostMapping("/generate-script")
    public ApiResponse<Map<String, Object>> generateScript(
            @RequestBody DifyGenerateScriptRequest request) {
        return ApiResponse.ok(difyAgentService.generateScript(request));
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
        return ApiResponse.ok(difyAgentService.generateImage(request));
    }

    /**
     * Dify Agent 视频生成（异步模式）。
     * 创建 Laozhang 视频任务后立即返回 taskId，Dify 通过 GET /status 轮询结果。
     * 避免同步阻塞导致 Squid/Docker 代理超时（视频生成需 2-5 分钟）。
     */
    @PostMapping("/generate-video")
    public ApiResponse<Map<String, String>> generateVideo(
            @RequestBody DifyGenerateVideoRequest request) {
        return ApiResponse.ok(difyAgentService.generateVideo(request));
    }

    /**
     * Dify Agent 查询视频任务状态。
     * 轮询此接口直到 status 为 completed 或 failed。
     */
    @GetMapping("/generate-video/status")
    public ApiResponse<Map<String, String>> pollVideoStatus(@RequestParam String taskId) {
        return ApiResponse.ok(difyAgentService.pollVideoStatus(taskId));
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
            @RequestParam(required = false) String projectId,  // 保留以兼容现有 Dify 工作流（方法内未实际使用）
            @RequestParam String prompt,
            @RequestParam(required = false) String sceneId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String quality,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String generatedImageUrl,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String picUrl,
            @RequestPart(required = false) List<MultipartFile> images) {
        return ApiResponse.ok(difyAgentService.generateImageMultipart(
            projectId, prompt, sceneId, model, size, quality, mode,
            generatedImageUrl, conversationId, picUrl, images));
    }

    /**
     * 清洗 Dify 传入的字符串值：未解析的 Dify 变量引用（含 structured_output.）
     * 视为无效值，返回 null 让 service 层使用默认值。
     *
     * 保留静态转发：AgentGenerationService 等外部调用方仍引用 DifyAgentController.sanitize，
     * 实际实现已下沉至 DifyAgentServiceImpl.sanitize。
     */
    public static String sanitize(String value) {
        return DifyAgentServiceImpl.sanitize(value);
    }
}
