package com.moon.xiaoyuecanvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.mapper.CanvasAssetMapper;
import com.moon.xiaoyuecanvas.service.CanvasAssetService;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CanvasAssetServiceImpl implements CanvasAssetService {

    private static final String FILE_URL_PREFIX = "/api/files/";

    private final CanvasAssetMapper canvasAssetMapper;
    private final MorrowCanvasService canvasService;
    private final AgentAssetMapper agentAssetMapper;
    private final FileStorageService fileStorageService;

    @Override
    public CanvasAsset upload(String userId, String canvasId, String producerNodeId,
                              String type, MultipartFile file) {
        MorrowCanvas canvas = canvasService.get(userId, canvasId);
        String normalizedType = normalizeType(type);
        String objectUrl = fileStorageService.saveUploadedReference(normalizedType, file);
        CanvasAsset asset = newAsset(
                userId, canvas, producerNodeId, normalizedType, objectUrl, null);
        canvasAssetMapper.insert(asset);
        return asset;
    }

    @Override
    public List<CanvasAsset> linkGeneration(String userId, String canvasId,
                                            String producerNodeId, String generationRecordId) {
        MorrowCanvas canvas = canvasService.get(userId, canvasId);
        AgentAsset generation = agentAssetMapper.selectById(generationRecordId);
        if (generation == null || !"completed".equals(generation.getStatus())) {
            throw new BusinessException(40401, "生成结果不存在或尚未完成");
        }
        if (!userId.equals(generation.getUserId())
                && (canvas.getProjectId() == null
                    || !canvas.getProjectId().equals(generation.getProjectId()))) {
            throw new BusinessException(40301, "无权映射该生成结果");
        }

        List<String> resultUrls = new ArrayList<>(JsonUtil.parseList(generation.getResultUrls()));
        if (resultUrls.isEmpty() && generation.getUrl() != null && !generation.getUrl().isBlank()) {
            resultUrls.add(generation.getUrl());
        }
        if (resultUrls.isEmpty()) {
            throw new BusinessException(50201, "生成结果没有可映射的媒体地址");
        }

        List<CanvasAsset> linked = new ArrayList<>();
        for (String resultUrl : resultUrls) {
            String durableUrl = ensureMinioObject(resultUrl, generation.getType());
            CanvasAsset existing = findExisting(canvasId, generationRecordId, durableUrl);
            if (existing != null) {
                linked.add(existing);
                continue;
            }
            CanvasAsset asset = newAsset(
                    userId, canvas, producerNodeId,
                    detectType(generation.getType(), durableUrl),
                    durableUrl, generationRecordId);
            try {
                canvasAssetMapper.insert(asset);
                linked.add(asset);
            } catch (DuplicateKeyException duplicate) {
                linked.add(findExisting(canvasId, generationRecordId, durableUrl));
            }
        }
        return linked;
    }

    @Override
    public List<CanvasAsset> list(String userId, String canvasId) {
        canvasService.get(userId, canvasId);
        return canvasAssetMapper.selectList(new LambdaQueryWrapper<CanvasAsset>()
                .eq(CanvasAsset::getCanvasId, canvasId)
                .orderByDesc(CanvasAsset::getCreatedAt));
    }

    @Override
    public CanvasAsset getOwned(String userId, String assetId) {
        CanvasAsset asset = canvasAssetMapper.selectById(assetId);
        if (asset == null) throw new BusinessException(40401, "画布资产不存在");
        canvasService.get(userId, asset.getCanvasId());
        return asset;
    }

    private CanvasAsset newAsset(String userId, MorrowCanvas canvas, String producerNodeId,
                                 String type, String objectUrl, String generationRecordId) {
        String filename = filename(objectUrl);
        CanvasAsset asset = new CanvasAsset();
        asset.setId(UUID.randomUUID().toString());
        asset.setCanvasId(canvas.getId());
        asset.setProjectId(canvas.getProjectId());
        asset.setUserId(userId);
        asset.setType(type);
        asset.setMimeType(FileStorageService.contentType(filename));
        asset.setObjectUrl(objectUrl);
        asset.setObjectKey(objectKey(objectUrl));
        asset.setGenerationRecordId(generationRecordId);
        asset.setProducerNodeId(producerNodeId);
        asset.setStatus("ready");
        return asset;
    }

    private CanvasAsset findExisting(String canvasId, String generationRecordId, String objectUrl) {
        return canvasAssetMapper.selectOne(new LambdaQueryWrapper<CanvasAsset>()
                .eq(CanvasAsset::getCanvasId, canvasId)
                .eq(CanvasAsset::getGenerationRecordId, generationRecordId)
                .eq(CanvasAsset::getObjectUrl, objectUrl)
                .last("LIMIT 1"));
    }

    private String ensureMinioObject(String url, String generationType) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(50201, "生成结果媒体地址为空");
        }
        if (url.startsWith(FILE_URL_PREFIX)) return url;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return "video".equals(detectType(generationType, url))
                    ? fileStorageService.saveVideo(url)
                    : fileStorageService.saveImage(url);
        }
        throw new BusinessException(50201, "生成结果不是可持久化的媒体地址: " + url);
    }

    private static String normalizeType(String type) {
        String value = type == null ? "image" : type.toLowerCase();
        return switch (value) {
            case "image", "video", "audio" -> value;
            default -> throw new BusinessException(40001, "不支持的画布资产类型: " + type);
        };
    }

    private static String detectType(String generationType, String url) {
        if ("image".equals(generationType) || "video".equals(generationType)) return generationType;
        String mime = FileStorageService.contentType(filename(url));
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("audio/")) return "audio";
        return "image";
    }

    private static String objectKey(String objectUrl) {
        if (objectUrl == null || !objectUrl.startsWith(FILE_URL_PREFIX)) {
            throw new BusinessException(50201, "资产地址不在 MinIO 文件路径下: " + objectUrl);
        }
        return objectUrl.substring(FILE_URL_PREFIX.length());
    }

    private static String filename(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }
}
