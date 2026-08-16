package com.storyboard.service;

import com.storyboard.dto.request.SceneRequest;
import com.storyboard.dto.response.SceneReferenceResponse;
import com.storyboard.dto.response.SceneResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 分镜服务接口：分镜列表、新增、更新、删除、参考素材管理。
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

    /** 分镜参考素材列表（按 sort_order）。 */
    List<SceneReferenceResponse> listReferences(String sceneId);

    /** 上传参考素材（type: image/video/audio；校验类型/数量上限/单文件大小上限），返回记录。 */
    SceneReferenceResponse uploadReference(String sceneId, String type, String purpose, MultipartFile file);

    /** 删除参考素材（含本地文件）。 */
    void deleteReference(String referenceId);
}
