package com.moon.moonagent.dto.request;

import java.util.List;

/** 单个分镜「完善图片」入队载荷：场景 id + 完善提示词 + 参数覆盖 + 参考素材。 */
public record StoryboardImageEnqueueItem(
        String sceneId,
        String prompt,
        String model,
        String size,
        String quality,
        Integer n,
        /** 当前分镜已生成图 URL（编辑源图），可为空（为空则后端读 scene.imageUrl）。 */
        String sourceImageUrl,
        /** 用户行内上传的参考图 URL（主后端相对路径 /api/files/... 或绝对 URL），可为空。 */
        List<String> refImageUrls
) {}
