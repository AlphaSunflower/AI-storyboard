package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.Size;
import java.util.List;

/** 批量分镜「完善图片」入队请求。 */
public record StoryboardImageEnqueueRequest(
        String conversationId,
        String projectId,
        @Size(max = 100, message = "单次最多入队 100 个分镜")
        List<StoryboardImageEnqueueItem> items
) {}
