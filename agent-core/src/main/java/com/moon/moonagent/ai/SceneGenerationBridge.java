package com.moon.moonagent.ai;

import java.util.List;

/**
 * 分镜场景回写桥——agent-core 生成服务的场景协作点。
 * agent-core 不依赖分镜 {@code Scene} 实体 / {@code StoryboardClient} / {@code AssetService}，
 * 由消费方（MoonAgent）实现并将 {@link SceneView} 与分镜场景双向映射。
 * 画布（XiaoYueCanvas）场景为 null：不提供该实现，生成服务走纯生成路径（sceneId=null），零影响。
 */
public interface SceneGenerationBridge {

    /** 读取场景生成相关字段；场景不存在返回 null。 */
    SceneView getScene(String sceneId);

    /** 按视频任务 ID 反查场景；无对应场景返回 null。 */
    SceneView getSceneByVideoTaskId(String taskId);

    /** 回写场景生成相关字段（实现负责将 SceneView 映射回分镜 Scene 并持久化）。 */
    void updateScene(SceneView view);

    /** 场景关联资产文字卡（人物一致性注入）；无则返回空字符串。 */
    String sceneAssetSheet(String sceneId, String type);

    /** 场景关联资产参考图（本地 /api/files/... 路径列表）；无则空列表。 */
    List<String> sceneReferenceImages(String sceneId, String type);
}
