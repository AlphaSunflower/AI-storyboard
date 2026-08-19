package com.storyboard.service;

import com.storyboard.dto.response.AssetVO;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneAsset;

import java.util.List;
import java.util.Map;

/**
 * 内部 API 业务接口 — 仅供 Agent 等内部服务调用（/api/internal/**，X-Internal-Token 鉴权）。
 * 数据访问与 VO 组装全部下沉到 Impl，Controller 不接触 Mapper/Entity。
 */
public interface InternalApiService {

    /** 查项目信息，不存在抛 40401 */
    Project getProject(String id);

    /** 查项目分镜列表（按 sceneNumber 升序） */
    List<Scene> getProjectScenes(String projectId);

    /** 批量写入分镜 */
    void batchInsertScenes(List<Scene> scenes);

    /** 清空项目分镜 */
    void deleteProjectScenes(String projectId);

    /** 查场景关联的资产 */
    List<SceneAsset> getSceneAssets(String sceneId);

    /** 关联资产到场景 */
    void linkSceneAssets(String sceneId, List<String> assetIds);

    /** 查单个场景，不存在抛 40401 */
    Scene getScene(String sceneId);

    /** 更新场景 */
    void updateScene(String sceneId, Scene scene);

    /**
     * 批量更新项目全部分镜的生成参数。
     * 覆盖列；空键跳过；无匹配键直接返回，避免无 SET 的非法 UPDATE。
     */
    void updateProjectSceneParams(String projectId, Map<String, String> params);

    /** 查项目可用资产（项目资产 ∪ 全局资产；AssetVO 无归属过滤——Agent 编排按会话用户上下文使用） */
    List<AssetVO> getProjectAssets(String projectId);

    /** 按 videoTaskId 查场景（视频异步轮询终态更新用），不存在抛 40401 */
    Scene getSceneByVideoTaskId(String videoTaskId);
}
