package com.storyboard.dto.request;

import java.util.List;

/**
 * 分镜关联资产（覆盖式设置）。
 */
public record SceneAssetsUpdateRequest(
    List<String> assetIds
) {}
