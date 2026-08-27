package com.storyboard.controller;

import com.storyboard.common.ApiResponse;
import com.storyboard.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    public ResponseEntity<Resource> getImage(@PathVariable String filename) {
        return serve(fileStorageService.readImage(filename), filename);
    }

    @GetMapping("/videos/{filename}")
    public ResponseEntity<Resource> getVideo(@PathVariable String filename) {
        return serve(fileStorageService.readVideo(filename), filename);
    }

    @GetMapping("/audios/{filename}")
    public ResponseEntity<Resource> getAudio(@PathVariable String filename) {
        return serve(fileStorageService.readAudio(filename), filename);
    }

    /** 对象不存在返回 404；存在则以推断的 Content-Type 返回对象字节。 */
    private ResponseEntity<Resource> serve(byte[] bytes, String filename) {
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType contentType = MediaType.parseMediaType(FileStorageService.contentType(filename));
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }
}
