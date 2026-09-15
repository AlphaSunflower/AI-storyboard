package com.moon.xiaoyuecanvas.service;

import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 画布资产与 MinIO 对象的持久映射服务。 */
public interface CanvasAssetService {

    CanvasAsset upload(String userId, String canvasId, String producerNodeId,
                       String type, MultipartFile file);

    List<CanvasAsset> linkGeneration(String userId, String canvasId,
                                     String producerNodeId, String generationRecordId);

    List<CanvasAsset> list(String userId, String canvasId);

    CanvasAsset getOwned(String userId, String assetId);
}
