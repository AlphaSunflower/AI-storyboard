package com.storyboard.service.impl;

import com.storyboard.dto.request.CreateProjectRequest;
import com.storyboard.dto.request.UpdateProjectRequest;
import com.storyboard.dto.response.ProjectResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.common.BusinessException;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.security.FileUrlSigner;
import com.storyboard.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 项目服务实现。
 */
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final ScenePromptMapper scenePromptMapper;
    private final FileUrlSigner fileUrlSigner;

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
        // 默认项目保护：最后一个项目禁止删除（保证始终有项目可进入，避免空态无法加分镜/与智能体沟通）
        if (projectMapper.findByUserId(userId).size() <= 1) {
            throw new BusinessException(40301, "至少保留一个项目");
        }
        projectMapper.deleteById(projectId);
    }

    @Override
    public ProjectResponse getLatestDraft(String userId) {
        Project draft = projectMapper.findLatestDraft(userId);
        return draft != null ? toResponse(draft) : null;
    }

    private ProjectResponse toResponse(Project project) {
        // 批量重置卡在 generating 超过 5 分钟的场景（单条 SQL 替代逐行 updateById）
        sceneMapper.resetStaleGenerating(project.getId());
        List<Scene> scenes = sceneMapper.findByProjectIdOrdered(project.getId());
        Map<String, ScenePrompt> prompts = scenes.isEmpty() ? Map.of()
            : scenePromptMapper.selectBatchIds(scenes.stream().map(Scene::getId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(ScenePrompt::getSceneId, p -> p));
        List<SceneResponse> sceneResponses = scenes.stream().map(s -> {
            ScenePrompt p = prompts.get(s.getId());
            return new SceneResponse(
            s.getId(), s.getProjectId(), s.getConversationId(), s.getSceneNumber(),
            p != null ? p.getScriptContent() : null,
            p != null ? p.getImagePrompt() : null,
            p != null ? p.getVideoPrompt() : null,
            p != null ? p.getNegativePrompt() : null,
            p != null ? p.getCameraMovement() : null,
            p != null ? p.getShotType() : null,
            p != null ? p.getSoundDesign() : null,
            p != null ? p.getAiModel() : null,
            p != null ? p.getVideoResolution() : null,
            p != null ? p.getDuration() : null,
            fileUrlSigner.signIfLocal(s.getImageUrl()),
            fileUrlSigner.signIfLocal(s.getVideoUrl()),
            s.getImageStatus(), s.getVideoStatus(), s.getVideoTaskId(),
            fileUrlSigner.signCommaSeparated(s.getImageUrls()),
            p != null ? p.getImageModel() : null,
            p != null ? p.getImageSize() : null,
            p != null ? p.getImageQuality() : null,
            p != null ? p.getImageN() : null,
            p != null ? p.getVideoModel() : null,
            p != null ? p.getVideoAspectRatio() : null,
            s.getCreatedAt(), s.getUpdatedAt()
        );
        }).toList();
        return new ProjectResponse(
            project.getId(), project.getUserId(), project.getName(),
            project.getDescription(), project.getCreationType(), project.getCustomTypeDesc(),
            project.getAspectRatio(), fileUrlSigner.signIfLocal(project.getReferenceImageUrl()),
            project.getScriptText(),
            project.getAiModel(), project.getStatus(),
            project.getCreatedAt(), project.getUpdatedAt(), sceneResponses
        );
    }
}
