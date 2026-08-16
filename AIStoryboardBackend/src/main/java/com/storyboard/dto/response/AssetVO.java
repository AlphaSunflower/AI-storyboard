package com.storyboard.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 资产 VO：含图集列表。
 *
 * @param projectId null = 用户全局资产库；非空 = 项目资产库
 */
public record AssetVO(
    String id,
    String type,
    String name,
    String description,
    String projectId,
    List<AssetImageVO> images,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
