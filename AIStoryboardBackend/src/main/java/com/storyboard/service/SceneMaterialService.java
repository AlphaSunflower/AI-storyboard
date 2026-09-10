package com.storyboard.service;

import com.storyboard.dto.request.SceneMaterialsUpdateRequest;

/** 分镜素材聚合服务：AI 资产关联 + 产出/分镜图片引用。 */
public interface SceneMaterialService {

    void replaceMaterials(String userId, String sceneId, SceneMaterialsUpdateRequest request);
}
