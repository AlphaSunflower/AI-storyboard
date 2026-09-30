package com.moon.moonagent.service;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates the project, conversation, and scene scope of queue writes. */
@Component
@RequiredArgsConstructor
public class StoryboardQueueRequestGuard {

    private final StoryboardClient storyboardClient;
    private final AgentConversationMapper conversationMapper;

    public void requireOwnedScope(String userId, String projectId, String conversationId,
                                  List<String> sceneIds) {
        if (userId == null || userId.isBlank() || projectId == null || projectId.isBlank()) {
            throw new BusinessException(40301, "无权访问该项目");
        }
        Map<String, Object> project = storyboardClient.getProject(projectId);
        if (project == null || !userId.equals(String.valueOf(project.get("userId")))) {
            throw new BusinessException(40301, "无权访问该项目");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException(40001, "会话ID不能为空");
        }
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null
                || !userId.equals(conversation.getUserId())
                || !projectId.equals(conversation.getProjectId())) {
            throw new BusinessException(40401, "会话不存在或不属于该项目");
        }

        List<String> requested = sceneIds == null ? List.of() : sceneIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .toList();
        if (requested.isEmpty()) {
            throw new BusinessException(40001, "分镜ID不能为空");
        }
        List<Scene> scenes = storyboardClient.getProjectScenes(projectId);
        Set<String> allowed = (scenes == null ? List.<Scene>of() : scenes).stream()
                .map(Scene::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (!allowed.containsAll(requested)) {
            throw new BusinessException(40401, "分镜不存在或不属于该项目");
        }
    }
}
