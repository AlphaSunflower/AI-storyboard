package com.storyboard.controller;

import com.storyboard.common.ApiResponse;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.dto.response.SceneFull;
import com.storyboard.dto.response.SceneReferenceResponse;
import com.storyboard.dto.request.SceneReferenceLinkRequest;
import com.storyboard.entity.Project;
import com.storyboard.entity.SceneAsset;
import com.storyboard.asset.client.AssetContracts.ProjectOwnership;
import com.storyboard.service.InternalApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 内部 API — 仅供 Agent 等内部服务调用，前端不可达（Gateway 屏蔽 /api/internal/**）。
 * 鉴权：由 InternalApiWebConfig 注册的 InternalApiAuthInterceptor 统一校验 X-Internal-Token 共享密钥，
 * 不再逐方法手动 checkToken（避免新增端点漏校验）；数据访问全部委托 InternalApiService。
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalApiController {

    private final InternalApiService internalApiService;

    /** 查项目信息 */
    @GetMapping("/projects/{id}")
    public ApiResponse<Project> getProject(@PathVariable String id) {
        return ApiResponse.ok(internalApiService.getProject(id));
    }

    /** 项目归属只读校验（asset-service 不读取 public.projects）。 */
    @GetMapping("/projects/{id}/ownership")
    public ApiResponse<ProjectOwnership> getProjectOwnership(@PathVariable String id) {
        return ApiResponse.ok(internalApiService.getProjectOwnership(id));
    }

    /** 查项目分镜列表 */
    @GetMapping("/projects/{id}/scenes")
    public ApiResponse<List<SceneFull>> getProjectScenes(@PathVariable String id) {
        return ApiResponse.ok(internalApiService.getProjectScenes(id));
    }

    /** 批量写入分镜 */
    @PostMapping("/scenes/batch")
    public ApiResponse<String> batchInsertScenes(@RequestBody List<SceneFull> scenes) {
        internalApiService.batchInsertScenes(scenes);
        return ApiResponse.ok("ok");
    }

    /** 事务内全量替换项目分镜，返回按输入顺序生成的新 sceneId。 */
    @PostMapping("/scenes/project/{projectId}/replace")
    public ApiResponse<List<String>> replaceProjectScenes(
            @PathVariable String projectId,
            @RequestBody List<SceneFull> scenes) {
        return ApiResponse.ok(internalApiService.replaceProjectScenes(projectId, scenes));
    }

    /** 事务内追加项目分镜，保留现有分镜并从最大 sceneNumber 后连续编号。 */
    @PostMapping("/scenes/project/{projectId}/append")
    public ApiResponse<List<String>> appendProjectScenes(
            @PathVariable String projectId,
            @RequestBody List<SceneFull> scenes) {
        return ApiResponse.ok(internalApiService.appendProjectScenes(projectId, scenes));
    }

    /** 清空项目分镜 */
    @DeleteMapping("/scenes/project/{projectId}")
    public ApiResponse<String> deleteProjectScenes(@PathVariable String projectId) {
        internalApiService.deleteProjectScenes(projectId);
        return ApiResponse.ok("ok");
    }

    /** 删除单个项目分镜并重排剩余编号。 */
    @DeleteMapping("/scenes/{sceneId}")
    public ApiResponse<String> deleteScene(@PathVariable String sceneId) {
        internalApiService.deleteScene(sceneId);
        return ApiResponse.ok("ok");
    }

    /** 查场景关联的资产 */
    @GetMapping("/scenes/{sceneId}/assets")
    public ApiResponse<List<SceneAsset>> getSceneAssets(@PathVariable String sceneId) {
        return ApiResponse.ok(internalApiService.getSceneAssets(sceneId));
    }

    /** 关联资产到场景 */
    @PostMapping("/scenes/{sceneId}/assets")
    public ApiResponse<String> linkSceneAssets(@PathVariable String sceneId,
                                                @RequestBody List<String> assetIds) {
        internalApiService.linkSceneAssets(sceneId, assetIds);
        return ApiResponse.ok("ok");
    }

    /** 挂载参考素材 URL 到场景（Agent 生成分镜后自动关联） */
    @PostMapping("/scenes/{sceneId}/reference-links")
    public ApiResponse<String> linkSceneReferences(@PathVariable String sceneId,
                                                   @RequestBody List<SceneReferenceLinkRequest> links) {
        internalApiService.linkSceneReferences(sceneId, links);
        return ApiResponse.ok("ok");
    }

    /** 查场景参考素材（含 role） */
    @GetMapping("/scenes/{sceneId}/references")
    public ApiResponse<List<SceneReferenceResponse>> getSceneReferences(@PathVariable String sceneId) {
        return ApiResponse.ok(internalApiService.getSceneReferences(sceneId));
    }

    /** 查单个场景 */
    @GetMapping("/scenes/{sceneId}")
    public ApiResponse<SceneFull> getScene(@PathVariable String sceneId) {
        return ApiResponse.ok(internalApiService.getScene(sceneId));
    }

    /** 更新场景 */
    @PutMapping("/scenes/{sceneId}")
    public ApiResponse<String> updateScene(@PathVariable String sceneId, @RequestBody SceneFull scene) {
        internalApiService.updateScene(sceneId, scene);
        return ApiResponse.ok("ok");
    }

    /** 批量更新项目全部分镜的生成参数（POST：内部 RestTemplate 的 JDK 客户端不支持 PATCH） */
    @PostMapping("/scenes/project/{projectId}/params")
    public ApiResponse<String> updateProjectSceneParams(@PathVariable String projectId,
                                                        @RequestBody Map<String, String> params) {
        internalApiService.updateProjectSceneParams(projectId, params);
        return ApiResponse.ok("ok");
    }

    /** 查项目可用资产（项目资产 ∪ 全局资产） */
    @GetMapping("/projects/{projectId}/assets")
    public ApiResponse<List<AssetVO>> getProjectAssets(@PathVariable String projectId) {
        return ApiResponse.ok(internalApiService.getProjectAssets(projectId));
    }

    /** 按 videoTaskId 查场景（视频异步轮询终态更新用；路径字面量 by-video-task 优先于 {sceneId} 匹配） */
    @GetMapping("/scenes/by-video-task/{videoTaskId}")
    public ApiResponse<SceneFull> getSceneByVideoTaskId(@PathVariable String videoTaskId) {
        return ApiResponse.ok(internalApiService.getSceneByVideoTaskId(videoTaskId));
    }

}
