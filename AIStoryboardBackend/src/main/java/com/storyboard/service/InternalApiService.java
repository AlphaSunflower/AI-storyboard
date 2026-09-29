package com.storyboard.service;

import com.storyboard.dto.response.AssetVO;
import com.storyboard.dto.response.SceneFull;
import com.storyboard.dto.response.SceneReferenceResponse;
import com.storyboard.dto.request.SceneReferenceLinkRequest;
import com.storyboard.entity.Project;
import com.storyboard.entity.SceneAsset;
import com.storyboard.asset.client.AssetContracts.ProjectOwnership;

import java.util.List;
import java.util.Map;

/**
 * 内部 API 业务接口 — 仅供 Agent 等内部服务调用（/api/internal/**，X-Internal-Token 鉴权）。
 * 数据访问与 VO 组装全部下沉到 Impl，Controller 不接触 Mapper/Entity。
 */
public interface InternalApiService {

    /** 查项目信息，不存在抛 40401 */
    Project getProject(String id);

    /** 项目归属只读校验（asset-service 专用；不返回密码等敏感信息）。 */
    ProjectOwnership getProjectOwnership(String id);

    /** 查项目分镜列表（按 sceneNumber 升序） */
    List<SceneFull> getProjectScenes(String projectId);

    /** 批量写入分镜 */
    void batchInsertScenes(List<SceneFull> scenes);

    /** 事务内全量替换项目分镜，返回按输入顺序生成的新 sceneId。 */
    List<String> replaceProjectScenes(String projectId, List<SceneFull> scenes);

    /** 事务内追加项目分镜，保留现有分镜并从最大 sceneNumber 后连续编号。 */
    List<String> appendProjectScenes(String projectId, List<SceneFull> scenes);

    /** 清空项目分镜 */
    void deleteProjectScenes(String projectId);

    /** 删除单个分镜。 */
    void deleteScene(String sceneId);

    /** 查场景关联的资产 */
    List<SceneAsset> getSceneAssets(String sceneId);

    /** 关联资产到场景 */
    void linkSceneAssets(String sceneId, List<String> assetIds);

    /** 挂载参考素材 URL 到场景（Agent 生成分镜后自动关联；按 URL 去重） */
    void linkSceneReferences(String sceneId, List<SceneReferenceLinkRequest> links);

    /** 查场景参考素材（含 role，供视频生成按首帧/尾帧/参考映射） */
    List<SceneReferenceResponse> getSceneReferences(String sceneId);

    /** 查单个场景，不存在抛 40401 */
    SceneFull getScene(String sceneId);

    /** 更新场景 */
    void updateScene(String sceneId, SceneFull scene);

    /**
     * 批量更新项目全部分镜的生成参数。
     * 覆盖列；空键跳过；无匹配键直接返回，避免无 SET 的非法 UPDATE。
     */
    void updateProjectSceneParams(String projectId, Map<String, String> params);

    /** 查项目可用资产（项目资产 ∪ 全局资产；AssetVO 无归属过滤——Agent 编排按会话用户上下文使用） */
    List<AssetVO> getProjectAssets(String projectId);

    /** 按 videoTaskId 查场景（视频异步轮询终态更新用），不存在抛 40401 */
    SceneFull getSceneByVideoTaskId(String videoTaskId);

    /** Replace only system-managed video frame references while preserving user uploads. */
    void replaceManagedVideoFrames(String sceneId, List<SceneReferenceLinkRequest> links);
}
