package com.storyboard.controller;

import com.storyboard.common.ApiResponse;
import com.storyboard.security.FileUrlSigner;
import com.storyboard.service.FileStorageService;
import com.storyboard.service.ImageThumbnailService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;
    private final FileUrlSigner fileUrlSigner;
    private final ImageThumbnailService imageThumbnailService;

    @Value("${files.allow-unsigned-legacy:false}")
    private boolean allowUnsignedLegacy;

    /**
     /** 上传图片，返回可访问路径。校验（扩展名白名单+content-type）收敛在 FileStorageService.saveUploadedImage。 */
     @PostMapping("/upload")
     public ApiResponse<Map<String, String>> upload(@RequestParam MultipartFile file) {
         String url = fileStorageService.saveUploadedImage(file);
         String filename = url.substring(url.lastIndexOf('/') + 1);
         return ApiResponse.ok(Map.of("url", url, "filename", filename));
     }

    @GetMapping("/images/{filename}")
    public ResponseEntity<Resource> getImage(@PathVariable String filename,
                                             @RequestParam(required = false) String token,
                                             @RequestParam(name = "w", required = false) Integer width,
                                             HttpServletRequest request) {
        verify(request, token);
        byte[] bytes = fileStorageService.readImage(filename);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        if (width != null) {
            var thumb = imageThumbnailService.thumbnail(filename, bytes, width);
            if (thumb != null) {
                // 文件名是内容哈希 + 宽度进了 URL：同一张缩略图永远同一份内容，可以放心长缓存
                return serve(thumb.bytes(), filename, false, thumb.contentType(),
                        "public, max-age=604800, immutable");
            }
        }
        return serve(bytes, filename);
    }

    @GetMapping("/videos/{filename}")
    public ResponseEntity<Resource> getVideo(@PathVariable String filename,
                                             @RequestParam(required = false) String token,
                                             HttpServletRequest request) {
        verify(request, token);
        return serve(fileStorageService.readVideo(filename), filename);
    }

    @GetMapping("/audios/{filename}")
    public ResponseEntity<Resource> getAudio(@PathVariable String filename,
                                             @RequestParam(required = false) String token,
                                             HttpServletRequest request) {
        verify(request, token);
        return serve(fileStorageService.readAudio(filename), filename);
    }

    @GetMapping("/files/{filename}")
    public ResponseEntity<Resource> getFile(@PathVariable String filename,
                                            @RequestParam(required = false) String token,
                                            @RequestParam(required = false, defaultValue = "false")
                                            boolean download,
                                            HttpServletRequest request) {
        verify(request, token);
        return serve(fileStorageService.readFile(filename), filename, download);
    }

    private void verify(HttpServletRequest request, String token) {
        if (allowUnsignedLegacy && (token == null || token.isBlank())) return;
        fileUrlSigner.verifyPath(request.getRequestURI(), token);
    }

    /** 对象不存在返回 404；存在则以推断的 Content-Type 返回对象字节。 */
    private ResponseEntity<Resource> serve(byte[] bytes, String filename) {
        return serve(bytes, filename, false);
    }

    private ResponseEntity<Resource> serve(byte[] bytes, String filename, boolean download) {
        return serve(bytes, filename, download, null, null);
    }

    /**
     * 内联展示或作为附件下载。显式写出 Content-Disposition，避免框架兜底成 {@code filename=f.txt}
     * 导致下载文件扩展名错误；文件名按 RFC 5987 编码，保留中文原名。
     *
     * @param contentTypeOverride 缩略图等派生内容用；null 表示按文件名推断
     * @param cacheControl        可缓存响应（内容哈希命名）用；null 表示不设
     */
    private ResponseEntity<Resource> serve(byte[] bytes, String filename, boolean download,
                                           String contentTypeOverride, String cacheControl) {
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        boolean inline = !download && FileStorageService.inlineDisplayable(filename);
        MediaType contentType = contentTypeOverride != null
                ? MediaType.parseMediaType(contentTypeOverride)
                : inline
                ? MediaType.parseMediaType(FileStorageService.contentType(filename))
                : MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition disposition = (inline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(bytes.length);
        if (cacheControl != null && !cacheControl.isBlank()) {
            response.header(HttpHeaders.CACHE_CONTROL, cacheControl);
        }
        if (inline && FileStorageService.sandboxedInline(filename)) {
            response.header("Content-Security-Policy", "sandbox");
        }
        return response.body(new ByteArrayResource(bytes));
    }
}
