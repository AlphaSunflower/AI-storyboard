package com.storyboard.service.impl;

import com.storyboard.dto.request.CreateProjectRequest;
import com.storyboard.dto.request.UpdateProjectRequest;
import com.storyboard.dto.response.ProjectResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 项目服务实现。
 */
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;

    @Override
    public List<ProjectResponse> listByUser(String userId) {
        return projectMapper.findByUserId(userId).stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public ProjectResponse create(String userId, CreateProjectRequest request) {
        Project project = new Project();
        project.setUserId(userId);
        project.setName(request.name() != null ? request.name() : "未命名项目");
        project.setDescription(request.description());
        project.setCreationType(request.creationType() != null ? request.creationType() : "movie");
        project.setAspectRatio(request.aspectRatio() != null ? request.aspectRatio() : "16:9");
        project.setStatus("draft");
        projectMapper.insert(project);
        return toResponse(project);
    }

    @Override
    public ProjectResponse getById(String userId, String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || !project.getUserId().equals(userId)) {
            throw new BusinessException(40401, "项目不存在");
        }
        return toResponse(project);
    }

    @Override
    @Transactional
    public ProjectResponse update(String userId, String projectId, UpdateProjectRequest request) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || !project.getUserId().equals(userId)) {
            throw new BusinessException(40401, "项目不存在");
        }
        if (request.name() != null) project.setName(request.name());
        if (request.description() != null) project.setDescription(request.description());
        if (request.scriptText() != null) project.setScriptText(request.scriptText());
        if (request.creationType() != null) project.setCreationType(request.creationType());
        if (request.customTypeDesc() != null) project.setCustomTypeDesc(request.customTypeDesc());
        if (request.aspectRatio() != null) project.setAspectRatio(request.aspectRatio());
        if (request.referenceImageUrl() != null) project.setReferenceImageUrl(request.referenceImageUrl());
        if (request.aiModel() != null) project.setAiModel(request.aiModel());
        if (request.status() != null) project.setStatus(request.status());
        projectMapper.updateById(project);
        return toResponse(project);
    }

    @Override
    @Transactional
    public void delete(String userId, String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || !project.getUserId().equals(userId)) {
            throw new BusinessException(40401, "项目不存在");
        }
        projectMapper.deleteById(projectId);
    }

    @Override
    public ProjectResponse getLatestDraft(String userId) {
        Project draft = projectMapper.findLatestDraft(userId);
        return draft != null ? toResponse(draft) : null;
    }

    private ProjectResponse toResponse(Project project) {
        List<Scene> scenes = sceneMapper.findByProjectIdOrdered(project.getId());
        List<SceneResponse> sceneResponses = scenes.stream().map(s -> new SceneResponse(
            s.getId(), s.getProjectId(), s.getSceneNumber(),
            s.getScriptContent(), s.getImagePrompt(), s.getVideoPrompt(),
            s.getNegativePrompt(), s.getCameraMovement(), s.getShotType(),
            s.getSoundDesign(), s.getAiModel(), s.getVideoResolution(),
            s.getDuration(), s.getImageUrl(), s.getVideoUrl(),
            s.getImageStatus(), s.getVideoStatus(), s.getCreatedAt(), s.getUpdatedAt()
        )).toList();
        return new ProjectResponse(
            project.getId(), project.getUserId(), project.getName(),
            project.getDescription(), project.getCreationType(), project.getCustomTypeDesc(),
            project.getAspectRatio(), project.getReferenceImageUrl(), project.getScriptText(),
            project.getAiModel(), project.getStatus(),
            project.getCreatedAt(), project.getUpdatedAt(), sceneResponses
        );
    }
}
