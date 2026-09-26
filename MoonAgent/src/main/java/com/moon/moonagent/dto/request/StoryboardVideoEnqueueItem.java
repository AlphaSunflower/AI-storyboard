package com.moon.moonagent.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/** 单个分镜的入队载荷：场景 id + 提示词覆盖 + 参数配置 + 参考素材覆盖。 */
public record StoryboardVideoEnqueueItem(
        @JsonProperty("sceneId") String sceneId,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("model") String model,
        @JsonProperty("resolution") String resolution,
        @JsonProperty("size") String size,
        @JsonProperty("aspectRatio") String aspectRatio,
        @JsonProperty("duration") Integer duration,
        @JsonProperty("negativePrompt") String negativePrompt,
        /** 分镜已生成图 URL（作视频首帧），可为空（为空则后端读 scene.imageUrl）。 */
        @JsonProperty("sourceImageUrl") String sourceImageUrl,
        /** 用户行内上传的参考图 URL（主后端相对路径 /api/files/... 或绝对 URL），可为空。 */
        @JsonProperty("refImageUrls") @JsonDeserialize(as = java.util.ArrayList.class) List<String> refImageUrls,
        /** 资产库关联的资产 ID，可为空；优先读该分镜已关联的「视频用途」资产图。 */
        @JsonProperty("refAssetIds") @JsonDeserialize(as = java.util.ArrayList.class) List<String> refAssetIds,
        /** 可选：带角色的参考图 [{url, role}]（role=FIRST_FRAME/LAST_FRAME/REFERENCE/FULL_REFERENCE）；
         *  为空回退 refImageUrls 全部按 REFERENCE 处理。 */
        @JsonProperty("refImageRoles") @JsonDeserialize(as = java.util.ArrayList.class) List<java.util.Map<String, String>> refImageRoles
) {
    @JsonCreator
    public StoryboardVideoEnqueueItem {}
}
