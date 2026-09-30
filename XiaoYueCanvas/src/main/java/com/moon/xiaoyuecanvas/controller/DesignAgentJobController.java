package com.moon.xiaoyuecanvas.controller;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.service.FileStorageService;
import com.moon.xiaoyuecanvas.dto.request.JobRequest;
import com.moon.xiaoyuecanvas.dto.request.CanvasLibraryImportRequest;
import com.moon.xiaoyuecanvas.dto.response.CapabilityDescriptor;
import com.moon.xiaoyuecanvas.dto.response.JobSubmitResponse;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.dto.response.CanvasProjectLibraryResponse;
import com.moon.xiaoyuecanvas.service.CanvasAssetService;
import com.moon.xiaoyuecanvas.service.CanvasProjectLibraryService;
import com.moon.xiaoyuecanvas.service.DesignJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.storyboard.common.BusinessException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Morrow 设计助理作业契约端点：
 * <ul>
 *   <li>GET  /health                → {label}</li>
 *   <li>GET  /capabilities          → 远程能力数组</li>
 *   <li>POST /jobs                  → {jobId}</li>
 *   <li>GET  /jobs/{jobId}/events   → SSE（queued/started/progress/agent.message/asset.created/storyboard.created/completed/failed/cancelled）</li>
 *   <li>POST /jobs/{jobId}/cancel   → 取消</li>
 *   <li>GET  /assets/{assetId}      → 素材文件（同源）</li>
 * </ul>
 * 全部端点 JWT 鉴权（{@code userId} 由 {@code JwtAuthFilter} 注入 request attribute）。
 */
@RestController
@RequiredArgsConstructor
public class DesignAgentJobController {

    private final DesignJobService designJobService;
    private final CanvasAssetService canvasAssetService;
    private final CanvasProjectLibraryService canvasProjectLibraryService;
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
                             @PathVariable String jobId,
                             @RequestParam(value = "afterSeq", required = false) Long afterSeq,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return designJobService.subscribeAndRun(userId, jobId, afterSeq, lastEventId);
    }

    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> status(@RequestAttribute("userId") String userId,
                                      @PathVariable String jobId) {
        return designJobService.status(userId, jobId);
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
    public ResponseEntity<ByteArrayResource> asset(@RequestAttribute("userId") String userId,
                                                    @PathVariable String assetId) {
        CanvasAsset asset = canvasAssetService.getOwned(userId, assetId);
        if (asset.getObjectUrl() != null && asset.getObjectUrl().startsWith("/api/files/")) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
                    .location(URI.create(asset.getObjectUrl()))
                    .build();
        }
        String filename = asset.getObjectUrl().substring(asset.getObjectUrl().lastIndexOf('/') + 1);
        byte[] data = switch (asset.getType()) {
            case "video" -> fileStorageService.readVideo(filename);
            case "audio" -> fileStorageService.readAudio(filename);
            default -> fileStorageService.readImage(filename);
        };
        if (data == null) throw new BusinessException(40401, "素材文件不存在");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getMimeType()))
                .body(new ByteArrayResource(data));
    }

    @PostMapping(value = "/assets/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CanvasAsset uploadAsset(@RequestAttribute("userId") String userId,
                                   @RequestParam String canvasId,
                                   @RequestParam(required = false) String producerNodeId,
                                   @RequestParam(defaultValue = "image") String type,
                                   @RequestParam MultipartFile file) {
        return canvasAssetService.upload(userId, canvasId, producerNodeId, type, file);
    }

    @PostMapping("/assets/link-generation")
    public List<CanvasAsset> linkGeneratedAssets(@RequestAttribute("userId") String userId,
                                                 @RequestBody Map<String, String> body) {
        String canvasId = body.get("canvasId");
        String generationRecordId = body.get("generationRecordId");
        if (canvasId == null || canvasId.isBlank()
                || generationRecordId == null || generationRecordId.isBlank()) {
            throw new BusinessException(40001, "canvasId 和 generationRecordId 不能为空");
        }
        return canvasAssetService.linkGeneration(
                userId, canvasId, body.get("producerNodeId"), generationRecordId);
    }

    @GetMapping("/canvases/{canvasId}/assets")
    public List<CanvasAsset> listCanvasAssets(@RequestAttribute("userId") String userId,
                                              @PathVariable String canvasId) {
        return canvasAssetService.list(userId, canvasId);
    }

    @GetMapping("/project-library")
    public CanvasProjectLibraryResponse projectLibrary(
            @RequestAttribute("userId") String userId,
            @RequestParam String projectId,
            @RequestParam(required = false) com.storyboard.asset.client.AssetContracts.AssetScope scope,
            @RequestParam(required = false) String folderId,
            @RequestParam(required = false) String type
    ) {
        return canvasProjectLibraryService.load(userId, projectId, scope, folderId, type);
    }

    @PostMapping("/project-library/import")
    public CanvasAsset importProjectLibraryAsset(
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody CanvasLibraryImportRequest request
    ) {
        return canvasProjectLibraryService.importAsset(
                userId, request.canvasId(), request.libraryAssetId(), request.producerNodeId());
    }

    @PostMapping("/project-library/import-group")
    public List<CanvasAsset> importProjectLibraryGroup(
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody CanvasLibraryImportRequest request
    ) {
        return canvasProjectLibraryService.importGroup(
                userId, request.canvasId(), request.libraryAssetId(), request.producerNodeId());
    }
}
