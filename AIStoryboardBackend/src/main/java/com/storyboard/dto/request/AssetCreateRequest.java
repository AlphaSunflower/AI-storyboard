package com.storyboard.dto.request;

/**
 * 创建资产请求。
 *
 * @param projectId 可空：null/空 = 用户全局资产库；非空 = 项目资产库
 */
public record AssetCreateRequest(
    String type,
    String name,
    String description,
    String projectId
) {}
