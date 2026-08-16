package com.storyboard.dto.request;

import java.util.List;

/**
 * 分镜关联资产（覆盖式设置，图片/视频用途分开）。
 */
public record SceneAssetsUpdateRequest(
    List<String> imageAssetIds,
    List<String> videoAssetIds
) {}
