package com.moon.moonagent.dto.request;

import java.util.List;

/** 批量分镜「完善图片」入队请求。 */
public record StoryboardImageEnqueueRequest(
        String conversationId,
        String projectId,
        List<StoryboardImageEnqueueItem> items
) {}
