package com.moon.moonagent.dto.request;

import java.util.List;

/** 单个分镜的入队载荷：场景 id + 提示词覆盖 + 参数配置 + 参考素材覆盖。 */
public record StoryboardVideoEnqueueItem(
        String sceneId,
        String prompt,
        String model,
        String resolution,
        String size,
        String aspectRatio,
        Integer duration,
        String negativePrompt,
        /** 分镜已生成图 URL（作视频首帧），可为空（为空则后端读 scene.imageUrl）。 */
        String sourceImageUrl,
        /** 用户行内上传的参考图 URL（主后端相对路径 /api/files/... 或绝对 URL），可为空。 */
        List<String> refImageUrls,
        /** 资产库关联的资产 ID，可为空；优先读该分镜已关联的「视频用途」资产图。 */
        List<String> refAssetIds,
        /** 可选：带角色的参考图 [{url, role}]（role=FIRST_FRAME/LAST_FRAME/REFERENCE/FULL_REFERENCE）；
         *  为空回退 refImageUrls 全部按 REFERENCE 处理。 */
        List<java.util.Map<String, String>> refImageRoles
) {}
