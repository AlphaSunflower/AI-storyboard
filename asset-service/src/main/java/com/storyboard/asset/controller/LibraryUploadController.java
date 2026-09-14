package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.dto.CompleteMultipartRequest;
import com.storyboard.asset.dto.MultipartInitRequest;
import com.storyboard.asset.service.MediaUploadService;
import com.storyboard.asset.storage.ObjectStorage;
import com.storyboard.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/library/uploads")
public class LibraryUploadController {

    private final MediaUploadService uploadService;

    public LibraryUploadController(MediaUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/direct")
    public ApiResponse<AssetSummary> direct(
            Authentication auth,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) AssetScope scope,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String relativePath,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(uploadService.uploadDirect(
                auth.getName(), projectId, scope, name, description, relativePath, file, idempotencyKey));
    }

    @PostMapping("/multipart/init")
    public ApiResponse<MediaUploadService.MultipartSession> init(
            Authentication auth,
            @Valid @RequestBody MultipartInitRequest request
    ) {
        return ApiResponse.ok(uploadService.initMultipart(
                auth.getName(), request.projectId(), request.scope(), request.fileName(),
                request.relativePath(), request.mimeType(), request.sizeBytes(), request.sha256()));
    }

    @GetMapping("/multipart/{sessionId}/parts/{partNumber}/url")
    public ApiResponse<Map<String, String>> partUrl(
            Authentication auth,
            @PathVariable String sessionId,
            @PathVariable int partNumber
    ) {
        return ApiResponse.ok(Map.of("url", uploadService.presignPart(auth.getName(), sessionId, partNumber)));
    }

    @PostMapping("/multipart/{sessionId}/complete")
    public ApiResponse<AssetSummary> complete(
            Authentication auth,
            @PathVariable String sessionId,
            @Valid @RequestBody CompleteMultipartRequest request
    ) {
        var parts = request.parts().stream()
                .map(part -> new ObjectStorage.CompletedPart(part.partNumber(), part.etag()))
                .toList();
        return ApiResponse.ok(uploadService.completeMultipart(auth.getName(), sessionId, parts));
    }

    @DeleteMapping("/multipart/{sessionId}")
    public ApiResponse<Void> cancel(Authentication auth, @PathVariable String sessionId) {
        uploadService.cancelMultipart(auth.getName(), sessionId);
        return ApiResponse.ok("上传已取消", null);
    }
}
