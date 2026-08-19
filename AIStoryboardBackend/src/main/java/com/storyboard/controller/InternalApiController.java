package com.storyboard.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.AssetImageVO;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.entity.Asset;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneAsset;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.AssetImageMapper;
import com.storyboard.mapper.AssetMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneAssetMapper;
import com.storyboard.mapper.SceneMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 内部 API — 仅供 Agent 等内部服务调用，前端不可达（Gateway 屏蔽 /api/internal/**）。
 * 鉴权：X-Internal-Token header 校验共享密钥。
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalApiController {

    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final SceneAssetMapper sceneAssetMapper;
    private final AssetMapper assetMapper;
    private final AssetImageMapper assetImageMapper;

    @Value("${internal.secret:moon-internal-secret-2024}")
    private String internalSecret;

    private void checkToken(HttpServletRequest request) {
        String token = request.getHeader("X-Internal-Token");
        if (token == null || !token.equals(internalSecret)) {
            throw new BusinessException(40301, "内部接口鉴权失败");
        }
    }

    /** 查项目信息 */
    @GetMapping("/projects/{id}")
    public ApiResponse<Project> getProject(@PathVariable String id, HttpServletRequest request) {
        checkToken(request);
        Project project = projectMapper.selectById(id);
        if (project == null) throw new BusinessException(40401, "项目不存在");
        return ApiResponse.ok(project);
    }

    /** 查项目分镜列表 */
    @GetMapping("/projects/{id}/scenes")
    public ApiResponse<List<Scene>> getProjectScenes(@PathVariable String id, HttpServletRequest request) {
        checkToken(request);
        List<Scene> scenes = sceneMapper.selectList(
                new LambdaQueryWrapper<Scene>().eq(Scene::getProjectId, id).orderByAsc(Scene::getSceneNumber));
        return ApiResponse.ok(scenes);
    }

    /** 批量写入分镜 */
    @PostMapping("/scenes/batch")
    public ApiResponse<String> batchInsertScenes(@RequestBody List<Scene> scenes, HttpServletRequest request) {
        checkToken(request);
        for (Scene scene : scenes) {
            sceneMapper.insert(scene);
        }
        return ApiResponse.ok("ok");
    }

    /** 清空项目分镜 */
    @DeleteMapping("/scenes/project/{projectId}")
    public ApiResponse<String> deleteProjectScenes(@PathVariable String projectId, HttpServletRequest request) {
        checkToken(request);
        sceneMapper.delete(new LambdaQueryWrapper<Scene>().eq(Scene::getProjectId, projectId));
        return ApiResponse.ok("ok");
    }

    /** 查场景关联的资产 */
    @GetMapping("/scenes/{sceneId}/assets")
    public ApiResponse<List<SceneAsset>> getSceneAssets(@PathVariable String sceneId, HttpServletRequest request) {
        checkToken(request);
        List<SceneAsset> assets = sceneAssetMapper.selectList(
                new LambdaQueryWrapper<SceneAsset>().eq(SceneAsset::getSceneId, sceneId));
        return ApiResponse.ok(assets);
    }

    /** 关联资产到场景 */
    @PostMapping("/scenes/{sceneId}/assets")
    public ApiResponse<String> linkSceneAssets(@PathVariable String sceneId,
                                                @RequestBody List<String> assetIds,
                                                HttpServletRequest request) {
        checkToken(request);
        for (String assetId : assetIds) {
            SceneAsset link = new SceneAsset();
            link.setSceneId(sceneId);
            link.setAssetId(assetId);
            sceneAssetMapper.insert(link);
        }
        return ApiResponse.ok("ok");
    }

    /** 查单个场景 */
    @GetMapping("/scenes/{sceneId}")
    public ApiResponse<Scene> getScene(@PathVariable String sceneId, HttpServletRequest request) {
        checkToken(request);
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "场景不存在");
        return ApiResponse.ok(scene);
    }

    /** 更新场景 */
    @PutMapping("/scenes/{sceneId}")
    public ApiResponse<String> updateScene(@PathVariable String sceneId, @RequestBody Scene scene, HttpServletRequest request) {
        checkToken(request);
        scene.setId(sceneId);
        sceneMapper.updateById(scene);
        return ApiResponse.ok("ok");
    }

    /** 批量更新项目全部分镜的生成参数（覆盖列；空键跳过；无匹配键直接返回，避免无 SET 的非法 UPDATE） */
    @PatchMapping("/scenes/project/{projectId}/params")
    public ApiResponse<String> updateProjectSceneParams(@PathVariable String projectId,
                                                        @RequestBody Map<String, String> params,
                                                        HttpServletRequest request) {
        checkToken(request);
        if (params == null || params.isEmpty()) return ApiResponse.ok("ok");
        LambdaUpdateWrapper<Scene> uw = new LambdaUpdateWrapper<Scene>().eq(Scene::getProjectId, projectId);
        putParam(uw, Scene::getImageModel, params.get("imageModel"));
        putParam(uw, Scene::getImageSize, params.get("imageSize"));
        putParam(uw, Scene::getImageQuality, params.get("imageQuality"));
        putParam(uw, Scene::getVideoModel, params.get("videoModel"));
        putParam(uw, Scene::getVideoResolution, params.get("videoResolution"));
        putParam(uw, Scene::getVideoAspectRatio, params.get("videoAspectRatio"));
        String d = params.get("videoDuration");
        if (d != null && !d.isBlank()) {
            try {
                uw.set(Scene::getDuration, Integer.parseInt(d.trim()));
            } catch (NumberFormatException ignored) {
                // 非法时长忽略
            }
        }
        String setSql = uw.getSqlSet();
        if (setSql == null || setSql.isBlank()) return ApiResponse.ok("ok");
        sceneMapper.update(null, uw);
        return ApiResponse.ok("ok");
    }

    private void putParam(LambdaUpdateWrapper<Scene> uw, SFunction<Scene, ?> col, String v) {
        if (v != null && !v.isBlank()) uw.set(col, v.trim());
    }

    /** 查项目可用资产（项目资产 ∪ 全局资产；AssetVO 无归属过滤——Agent 编排按会话用户上下文使用） */
    @GetMapping("/projects/{projectId}/assets")
    public ApiResponse<List<AssetVO>> getProjectAssets(@PathVariable String projectId, HttpServletRequest request) {
        checkToken(request);
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .and(w -> w.eq(Asset::getProjectId, projectId).or().isNull(Asset::getProjectId))
                .orderByDesc(Asset::getCreatedAt));
        List<AssetVO> vos = assets.stream().map(a -> {
            List<AssetImageVO> images = assetImageMapper.findByAssetId(a.getId()).stream()
                    .map(i -> new AssetImageVO(i.getId(), i.getUrl(), i.getSortOrder(), i.getFileName()))
                    .toList();
            return new AssetVO(a.getId(), a.getType(), a.getName(), a.getDescription(),
                    a.getProjectId(), images, a.getCreatedAt(), a.getUpdatedAt());
        }).toList();
        return ApiResponse.ok(vos);
    }

    /** 按 videoTaskId 查场景（视频异步轮询终态更新用；路径字面量 by-video-task 优先于 {sceneId} 匹配） */
    @GetMapping("/scenes/by-video-task/{videoTaskId}")
    public ApiResponse<Scene> getSceneByVideoTaskId(@PathVariable String videoTaskId, HttpServletRequest request) {
        checkToken(request);
        Scene scene = sceneMapper.selectOne(new LambdaQueryWrapper<Scene>().eq(Scene::getVideoTaskId, videoTaskId));
        if (scene == null) throw new BusinessException(40401, "场景不存在");
        return ApiResponse.ok(scene);
    }

}
