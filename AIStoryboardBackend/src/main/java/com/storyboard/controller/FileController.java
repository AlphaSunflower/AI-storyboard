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

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /**
     /** 上传图片，返回可访问路径。校验（扩展名白名单+content-type）收敛在 FileStorageService.saveUploadedImage。 */
     @PostMapping("/upload")
     public ApiResponse<Map<String, String>> upload(@RequestParam MultipartFile file) {
         String url = fileStorageService.saveUploadedImage(file);
         String filename = url.substring(url.lastIndexOf('/') + 1);
         return ApiResponse.ok(Map.of("url", url, "filename", filename));
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

    @GetMapping("/audios/{filename}")
    public ResponseEntity<Resource> getAudio(@PathVariable String filename) throws IOException {
        Path filePath = fileStorageService.resolveAudio(filename);
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
