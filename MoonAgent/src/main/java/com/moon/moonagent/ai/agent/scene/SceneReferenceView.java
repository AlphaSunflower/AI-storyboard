package com.moon.moonagent.ai.agent.scene;

import java.util.Map;

/**
 * 一条分镜引用的解析结果（{@code kind=scene} 的 {@code @分镜N} 标记）。
 *
 * <p>解析失败（不属于本轮会话所属项目 / 已删除 / 主后端不可用）时 {@code resolved=false}，
 * 由 {@link #reason()} 给出一句可直接注入提示词的说明；调用方不得据此中断本轮。
 *
 * @param token       正文里的标记原文（已做注入防护，见消费方）
 * @param sceneId     引用声明的分镜 id
 * @param sceneNumber 分镜号（解析不到时为 null）
 * @param resolved    是否解析成功
 * @param reason      解析失败原因（成功时为空串）
 * @param fields      解析成功时的中文标签 → 字段值（只含非空字段，顺序稳定）
 * @param hasImage    该分镜是否已有图片
 * @param hasVideo    该分镜是否已有视频
 */
public record SceneReferenceView(
        String token,
        String sceneId,
        Integer sceneNumber,
        boolean resolved,
        String reason,
        Map<String, String> fields,
        boolean hasImage,
        boolean hasVideo
) {
}
