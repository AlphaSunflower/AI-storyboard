package com.moon.moonagent.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/** 批量分镜视频入队请求。 */
public record StoryboardVideoEnqueueRequest(
        @JsonProperty("conversationId") String conversationId,
        @JsonProperty("projectId") String projectId,
        @JsonProperty("items") @JsonDeserialize(as = java.util.ArrayList.class) List<StoryboardVideoEnqueueItem> items
) {
    @JsonCreator
    public StoryboardVideoEnqueueRequest {}
}
