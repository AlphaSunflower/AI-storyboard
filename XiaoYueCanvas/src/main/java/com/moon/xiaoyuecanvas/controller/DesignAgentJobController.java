package com.moon.xiaoyuecanvas.controller;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.service.FileStorageService;
import com.moon.xiaoyuecanvas.dto.request.JobRequest;
import com.moon.xiaoyuecanvas.dto.response.CapabilityDescriptor;
import com.moon.xiaoyuecanvas.dto.response.JobSubmitResponse;
import com.moon.xiaoyuecanvas.service.DesignJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.storyboard.common.BusinessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Morrow 设计助理作业契约端点：
 * <ul>
 *   <li>GET  /health                → {label}</li>
 *   <li>GET  /capabilities          → 远程能力数组</li>
 *   <li>POST /jobs                  → {jobId}</li>
 *   <li>GET  /jobs/{jobId}/events   → SSE（queued/started/progress/agent.message/asset.created/completed/failed/cancelled）</li>
 *   <li>POST /jobs/{jobId}/cancel   → 取消</li>
 *   <li>GET  /assets/{assetId}      → 素材文件（同源）</li>
 * </ul>
 * 全部端点 JWT 鉴权（{@code userId} 由 {@code JwtAuthFilter} 注入 request attribute）。
 */
@RestController
@RequiredArgsConstructor
public class DesignAgentJobController {

    private final DesignJobService designJobService;
    private final FileStorageService fileStorageService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("label", "Design Agent");
    }

    @GetMapping("/capabilities")
    public List<CapabilityDescriptor> capabilities() {
        return designJobService.capabilities();
    }

    @PostMapping("/jobs")
    public JobSubmitResponse submit(@RequestAttribute("userId") String userId,
                                    @Valid @RequestBody JobRequest request) {
        return designJobService.submit(userId, request);
    }

    @GetMapping("/jobs/{jobId}/events")
    public SseEmitter events(@RequestAttribute("userId") String userId,
                             @PathVariable String jobId) {
        return designJobService.subscribeAndRun(userId, jobId);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public Map<String, Object> cancel(@RequestAttribute("userId") String userId,
                                      @PathVariable String jobId) {
        designJobService.cancel(userId, jobId);
        return Map.of("jobId", jobId);
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> listConversations(@RequestAttribute("userId") String userId,
                                                        @RequestParam String projectId) {
        return designJobService.listConversations(userId, projectId).stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "title", c.getTitle() == null ? "" : c.getTitle(),
                        "status", c.getStatus() == null ? "active" : c.getStatus(),
                        "updatedAt", c.getUpdatedAt() == null ? "" : c.getUpdatedAt().toString()))
                .toList();
    }

    @PostMapping("/conversations")
    public Map<String, Object> createConversation(@RequestAttribute("userId") String userId,
                                                  @RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        if (projectId == null || projectId.isBlank()) throw new BusinessException(40001, "projectId 不能为空");
        AgentConversation conv = designJobService.getOrCreateConversation(userId, projectId);
        return Map.of("id", conv.getId(), "title", conv.getTitle() == null ? "" : conv.getTitle());
    }

    @GetMapping("/conversations/{id}/messages")
    public List<Map<String, Object>> conversationMessages(@RequestAttribute("userId") String userId,
                                                          @PathVariable String id) {
        return designJobService.conversationMessages(userId, id).stream()
                .map(m -> Map.<String, Object>of(
                        "role", m.getRole(),
                        "content", m.getContent(),
                        "createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString()))
                .toList();
    }

    @GetMapping("/assets/{assetId}")
    public ResponseEntity<FileSystemResource> asset(@RequestAttribute("userId") String userId,
                                                    @PathVariable String assetId) throws IOException {
        AgentAsset asset = designJobService.getOwnedAsset(userId, assetId);
        String url = asset.getUrl();
        if (url == null) throw new BusinessException(40401, "素材无文件");
        String filename = url.substring(url.lastIndexOf('/') + 1);
        Path file = "video".equals(asset.getType())
                ? fileStorageService.resolveVideo(filename)
                : fileStorageService.resolveImage(filename);
        if (!Files.exists(file)) throw new BusinessException(40401, "素材文件不存在");
        String mime = "video".equals(asset.getType()) ? "video/mp4" : MediaType.IMAGE_PNG_VALUE;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .body(new FileSystemResource(file));
    }
}
