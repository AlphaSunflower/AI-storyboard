package com.storyboard.dto.request;

import java.util.List;

/**
 * 分镜排序请求。
 *
 * @param sceneIds 按新顺序排列的分镜 ID 列表（必须包含该项目下所有分镜）
 */
public record ReorderScenesRequest(List<String> sceneIds) {}
