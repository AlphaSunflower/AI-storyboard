package com.moon.moonagent.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 批量分镜视频入队请求。 */
public record StoryboardVideoEnqueueRequest(
        @JsonProperty("conversationId") String conversationId,
        @JsonProperty("projectId") String projectId,
        @JsonProperty("items")
        @Size(max = 100, message = "单次最多入队 100 个分镜")
        @JsonDeserialize(as = java.util.ArrayList.class) List<StoryboardVideoEnqueueItem> items
) {
    @JsonCreator
    public StoryboardVideoEnqueueRequest {}
}
