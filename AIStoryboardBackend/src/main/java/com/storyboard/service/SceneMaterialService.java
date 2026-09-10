package com.storyboard.service;

import com.storyboard.dto.request.SceneMaterialReferenceRequest;
import com.storyboard.dto.request.SceneMaterialsUpdateRequest;
import com.storyboard.dto.response.SceneReferenceResponse;

import java.util.List;

/** 分镜素材聚合服务：AI 资产关联 + 产出/分镜图片引用。 */
public interface SceneMaterialService {

    void replaceMaterials(String userId, String sceneId, SceneMaterialsUpdateRequest request);

    /** 追加产出/分镜图片引用，不覆盖用户上传素材与已关联资产。 */
    List<SceneReferenceResponse> linkReferences(String userId, String sceneId,
                                                List<SceneMaterialReferenceRequest> references);
}
