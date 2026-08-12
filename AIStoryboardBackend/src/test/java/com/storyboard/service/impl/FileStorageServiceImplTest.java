package com.storyboard.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上传图片校验单测：content-type 白名单（防改后缀上传 html/脚本）+ 扩展名白名单兜底。
 * 安全路径（FileController.upload 与 /api/agent/upload 两入口统一走 saveUploadedImage）。
 */
class FileStorageServiceImplTest {

    private final FileStorageServiceImpl service = new FileStorageServiceImpl();
    private final List<String> createdUrls = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // 测试真实写文件到 uploads/images/（IMAGES_DIR 为相对路径常量），跑完即删防堆积
        for (String url : createdUrls) {
            try {
                Path p = Paths.get("uploads/images", url.substring(url.lastIndexOf('/') + 1));
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        }
    }

    private String save(MockMultipartFile f) {
        String url = service.saveUploadedImage(f);
        createdUrls.add(url);
        return url;
    }

    @Test
    void 图片contentType_保存成功返回url() {
        MockMultipartFile f = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});
        String url = save(f);
        assertNotNull(url);
        assertTrue(url.startsWith("/api/files/images/"), "URL 前缀必须是 /api/files/images/");
        assertTrue(url.endsWith(".png"), "png 后缀保留");
    }

    @Test
    void 非图片contentType_拒绝() {
        // 改后缀上传 html：.png 实为 text/html 必须拒绝
        MockMultipartFile f = new MockMultipartFile("file", "a.png", "text/html", "<script>".getBytes());
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.saveUploadedImage(f));
        assertTrue(e.getMessage().contains("仅支持上传图片"));
    }

    @Test
    void 空contentType_拒绝() {
        MockMultipartFile f = new MockMultipartFile("file", "a.png", null, new byte[]{1});
        assertThrows(RuntimeException.class, () -> service.saveUploadedImage(f));
    }

    @Test
    void 空文件_拒绝() {
        MockMultipartFile f = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);
        assertThrows(RuntimeException.class, () -> service.saveUploadedImage(f));
    }

    @Test
    void 白名单外扩展名_回退png() {
        // image/* content-type 合法但扩展名 .jsp 非法 → 回退 .png（防御异常扩展名）
        MockMultipartFile f = new MockMultipartFile("file", "a.jsp", "image/png", new byte[]{1});
        String url = save(f);
        assertTrue(url.endsWith(".png"), "非法扩展名必须回退 png，实际: " + url);
    }

    @Test
    void 无扩展名_默认png() {
        MockMultipartFile f = new MockMultipartFile("file", "avatar", "image/png", new byte[]{1});
        String url = save(f);
        assertTrue(url.endsWith(".png"));
    }
}
