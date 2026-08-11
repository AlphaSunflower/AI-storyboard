package com.storyboard.dto.request;

/**
 * 分镜新增/更新请求。
 *
 * <p>新增（POST /api/projects/{projectId}/scenes）只用 scriptContent/imagePrompt/videoPrompt/
 * cameraMovement/shotType 五个字段，其余忽略，sceneNumber 由后端自动 +1。
 *
 * <p>更新（PUT /api/scenes/{id}）时 null 字段表示「不修改」，与旧 Map.containsKey 语义对齐
 * （旧实现显式传 null 也会 set 成 null，现约定：显式 null 视为不修改，前端不会传显式 null 清空字段）。
 */
public record SceneRequest(
        String scriptContent,
        String imagePrompt,
        String videoPrompt,
        String negativePrompt,
        String cameraMovement,
        String shotType,
        String soundDesign,
        String aiModel,
        String videoResolution,
        Integer duration
) {}
