package com.storyboard.dto.request;

import java.util.List;

/**
 * 分镜素材选择器的原子保存请求。
 * assetIds 覆盖 AI 资产关联；references 覆盖产出素材和当前分镜图片引用，用户直接上传素材不受影响。
 */
public record SceneMaterialsUpdateRequest(
        List<String> assetIds,
        List<SceneMaterialReferenceRequest> references
) {}
