package com.storyboard.controller;

import com.storyboard.common.ApiResponse;
import com.storyboard.security.FileUrlSigner;
import com.storyboard.service.FileStorageService;
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
                                             HttpServletRequest request) {
        verify(request, token);
        return serve(fileStorageService.readImage(filename), filename);
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

    /**
     * 内联展示或作为附件下载。显式写出 Content-Disposition，避免框架兜底成 {@code filename=f.txt}
     * 导致下载文件扩展名错误；文件名按 RFC 5987 编码，保留中文原名。
     */
    private ResponseEntity<Resource> serve(byte[] bytes, String filename, boolean download) {
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        boolean inline = !download && FileStorageService.inlineDisplayable(filename);
        MediaType contentType = inline
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
        if (inline && FileStorageService.sandboxedInline(filename)) {
            response.header("Content-Security-Policy", "sandbox");
        }
        return response.body(new ByteArrayResource(bytes));
    }
}
