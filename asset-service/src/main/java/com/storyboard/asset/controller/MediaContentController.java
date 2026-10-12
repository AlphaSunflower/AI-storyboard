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

    public MediaContentController(MediaContentService mediaContentService,
                                  AssetCatalogService catalogService) {
        this.mediaContentService = mediaContentService;
        this.catalogService = catalogService;
    }

    @GetMapping("/{mediaId}/metadata")
    public ApiResponse<MediaRef> metadata(Authentication auth, @PathVariable String mediaId) {
        return ApiResponse.ok(catalogService.getMedia(auth.getName(), mediaId));
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
