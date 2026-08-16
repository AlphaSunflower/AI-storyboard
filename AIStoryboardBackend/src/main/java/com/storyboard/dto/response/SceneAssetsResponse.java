package com.storyboard.dto.response;

import java.util.List;

/**
 * 分镜关联资产（按用途拆分为图片/视频两组）。
 */
public record SceneAssetsResponse(
    List<AssetVO> imageAssets,
    List<AssetVO> videoAssets
) {}
