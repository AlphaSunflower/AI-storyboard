package com.moon.moonagent.dto.request;

import java.util.List;

/** 批量分镜视频入队请求。 */
public record StoryboardVideoEnqueueRequest(
        String conversationId,
        String projectId,
        List<StoryboardVideoEnqueueItem> items
) {}
