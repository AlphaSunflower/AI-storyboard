package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.MediaContentService;
import com.storyboard.asset.storage.StoredObject;
import com.storyboard.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/library/media")
public class MediaContentController {

    private final MediaContentService mediaContentService;
    private final AssetCatalogService catalogService;
    private final com.storyboard.asset.service.ThumbnailService thumbnailService;

    public MediaContentController(MediaContentService mediaContentService,
                                  AssetCatalogService catalogService,
                                  com.storyboard.asset.service.ThumbnailService thumbnailService) {
        this.mediaContentService = mediaContentService;
        this.catalogService = catalogService;
        this.thumbnailService = thumbnailService;
    }

    @GetMapping("/{mediaId}/metadata")
    public ApiResponse<MediaRef> metadata(Authentication auth, @PathVariable String mediaId) {
        return ApiResponse.ok(catalogService.getMedia(auth.getName(), mediaId));
    }

    /** 图片缩略图：签名路径 + 尺寸，首次生成后写 MinIO 缓存。 */
    @GetMapping("/thumbnails")
    public ResponseEntity<StreamingResponseBody> thumbnail(
            @RequestParam String path,
            @RequestParam String token,
            @RequestParam(defaultValue = "320") int size
    ) throws Exception {
        StoredObject object = thumbnailService.open(path, token, size);
        StreamingResponseBody body = output -> {
            try (object) {
                object.stream().transferTo(output);
            }
        };
        String contentType = object.contentType() == null || object.contentType().isBlank()
                ? MediaType.IMAGE_JPEG_VALUE
                : object.contentType();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(object.contentLength())
                .body(body);
    }

    @GetMapping("/{mediaId}/content")
    public ResponseEntity<StreamingResponseBody> content(
            @PathVariable String mediaId,
            @RequestParam String token,
            HttpServletRequest request
    ) throws Exception {
        StoredObject object = mediaContentService.openSigned(mediaId, token, request.getHeader(HttpHeaders.RANGE));
        StreamingResponseBody body = output -> {
            try (object) {
                object.stream().transferTo(output);
            }
        };
        String contentType = object.contentType() == null || object.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : object.contentType();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(object.contentRange() == null ? 200 : 206)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(object.contentLength());
        if (object.contentRange() != null) response.header(HttpHeaders.CONTENT_RANGE, object.contentRange());
        return response.body(body);
    }
}
