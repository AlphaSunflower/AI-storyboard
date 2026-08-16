package com.storyboard.dto.response;

/**
 * 资产图 VO。
 */
public record AssetImageVO(
    String id,
    String url,
    Integer sortOrder,
    String fileName
) {}
