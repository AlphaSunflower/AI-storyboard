package com.storyboard.controller;

import com.storyboard.dto.response.ApiResponse;
import com.storyboard.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /**
     * 上传图片，返回可访问路径。
     * 前端上传后，将返回的 url 传入 Dify 作为文本变量，
     * Dify 再以 generatedImageUrl 传给 /api/ai/dify/generate-image 即可。
     */
    @PostMapping("/upload")
    public ApiResponse<Map<String, String>> upload(@RequestParam MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ApiResponse.error(400, "文件为空");
        }
        String ext = extension(file.getOriginalFilename());
        String filename = UUID.randomUUID() + ext;
        Path dest = fileStorageService.resolveImage(filename);
        Files.createDirectories(dest.getParent());
        file.transferTo(dest);

        String url = "/api/files/images/" + filename;
        return ApiResponse.ok(Map.of("url", url, "filename", filename));
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) return ".png";
        return filename.substring(filename.lastIndexOf('.'));
    }

    @GetMapping("/images/{filename}")
    public ResponseEntity<Resource> getImage(@PathVariable String filename) throws IOException {
        Path filePath = fileStorageService.resolveImage(filename);
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        var contentType = MediaType.parseMediaType(FileStorageService.contentType(filename));
        FileInputStream fis = new FileInputStream(filePath.toFile());
        InputStreamResource resource = new InputStreamResource(fis);

        return ResponseEntity.ok()
                .contentType(contentType)
                .body(resource);
    }

    @GetMapping("/videos/{filename}")
    public ResponseEntity<Resource> getVideo(@PathVariable String filename) throws IOException {
        Path filePath = fileStorageService.resolveVideo(filename);
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        var contentType = MediaType.parseMediaType(FileStorageService.contentType(filename));
        FileInputStream fis = new FileInputStream(filePath.toFile());
        InputStreamResource resource = new InputStreamResource(fis);

        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(Files.size(filePath))
                .body(resource);
    }
}
