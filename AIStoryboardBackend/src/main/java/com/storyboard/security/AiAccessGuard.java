package com.storyboard.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.common.BusinessException;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Ownership checks shared by the public AI generation endpoints. */
@Component
@RequiredArgsConstructor
public class AiAccessGuard {

    private final ProjectService projectService;
    private final SceneMapper sceneMapper;

    public void requireProject(String userId, String projectId) {
        if (userId == null || userId.isBlank() || projectId == null || projectId.isBlank()) {
            throw new BusinessException(40301, "无权访问该项目");
        }
        projectService.getById(userId, projectId);
    }

    public Scene requireScene(String userId, String sceneId) {
        if (sceneId == null || sceneId.isBlank()) {
            throw new BusinessException(40001, "场景ID不能为空");
        }
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException(40401, "场景不存在");
        }
        requireProject(userId, scene.getProjectId());
        return scene;
    }

    public Scene requireVideoTask(String userId, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(40001, "任务ID不能为空");
        }
        Scene scene = sceneMapper.selectOne(new LambdaQueryWrapper<Scene>()
                .eq(Scene::getVideoTaskId, taskId)
                .last("LIMIT 1"));
        if (scene == null) {
            throw new BusinessException(40401, "任务不存在");
        }
        requireProject(userId, scene.getProjectId());
        return scene;
    }
}
