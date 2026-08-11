package com.storyboard.service;

import com.storyboard.dto.request.SceneRequest;
import com.storyboard.dto.response.SceneResponse;

import java.util.List;

/**
 * 分镜服务接口：分镜列表、新增、更新、删除。
 */
public interface SceneService {

    /** 查询项目下全部分镜（按编号排序）。 */
    List<SceneResponse> listByProject(String projectId);

    /** 新增分镜（编号自动 +1，缺失字段用空串默认值）。 */
    SceneResponse addScene(String projectId, SceneRequest request);

    /** 更新分镜（仅更新 request 中非 null 字段）。 */
    SceneResponse updateScene(String sceneId, SceneRequest request);

    /** 删除分镜（不存在抛 40401）。 */
    void deleteScene(String sceneId);
}
