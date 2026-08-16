package com.storyboard.dto.request;

/**
 * 更新资产请求（仅改名/改文字约束，null 字段不修改）。
 */
public record AssetUpdateRequest(
    String name,
    String description
) {}
