package com.storyboard.controller;

import com.storyboard.service.FileStorageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
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
