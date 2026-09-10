package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.entity.Scene;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 构建分镜审查 HITL 卡片；手动“审查分镜”和分镜写库后的自动审查共用同一契约。 */
@Component
@RequiredArgsConstructor
public class SceneReviewCardService {

    private static final Logger log = LoggerFactory.getLogger(SceneReviewCardService.class);

    private final AgentOrchestratorSupport support;

    public String openReview(OrchestrationRequest request, List<Scene> scenes) {
        return openReview(request, scenes, "请选择要执行的操作：");
    }

    public String openReview(OrchestrationRequest request, List<Scene> scenes, String planText) {
        List<Scene> targetScenes = scenes == null ? List.of() : scenes;
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(Map.of("id", "review-optimize", "title", "优化方案"));
        if (targetScenes.stream().anyMatch(s -> s.getImageUrl() == null || s.getImageUrl().isBlank())) {
            actions.add(Map.of("id", "review-gen-image", "title", "生成图片"));
        }
        if (targetScenes.stream().anyMatch(s -> s.getVideoUrl() == null || s.getVideoUrl().isBlank())) {
            actions.add(Map.of("id", "review-gen-video", "title", "生成视频"));
        }
        actions.add(Map.of("id", "review-skip", "title", "跳过"));
        actions.add(Map.of("id", "custom", "title", "✍ 自定义输入"));
        actions.add(Map.of("id", "review-video-auto", "title", "全自动生成视频（直接进入生视频队列）"));

        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText == null || planText.isBlank() ? "请选择要执行的操作：" : planText,
                "review-action",
                List.of(Map.of(
                        "sceneNums", targetScenes.stream().map(Scene::getSceneNumber).toList(),
                        "projectId", request.getConversation().getProjectId())),
                "human_input",
                actions,
                List.of(),
                Map.of(),
                Map.of(),
                safeModels("image"),
                safeModels("video"),
                buildSceneOptions(targetScenes)));
    }

    private List<Map<String, Object>> safeModels(String type) {
        try {
            List<Map<String, Object>> models = support.buildModels(type);
            return models == null ? List.of() : models;
        } catch (Exception e) {
            log.warn("SceneReviewCardService: 获取{}模型列表失败，降级为空: {}", type, e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> buildSceneOptions(List<Scene> scenes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scene scene : scenes) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", scene.getId());
            String desc = scene.getScriptContent();
            if (desc != null && desc.length() > 30) desc = desc.substring(0, 30) + "…";
            item.put("name", "分镜" + scene.getSceneNumber() + ": " + (desc != null ? desc : ""));
            item.put("type", "scene");
            if (scene.getImageUrl() != null && !scene.getImageUrl().isBlank()) item.put("image", scene.getImageUrl());
            List<String> imageUrls = imageUrls(scene);
            if (!imageUrls.isEmpty()) item.put("imageUrls", imageUrls);
            item.put("sceneNumber", scene.getSceneNumber());
            item.put("scriptContent", scene.getScriptContent());
            item.put("hasImage", scene.getImageUrl() != null && !scene.getImageUrl().isBlank());
            item.put("hasVideo", scene.getVideoUrl() != null && !scene.getVideoUrl().isBlank());
            item.put("videoStatus", scene.getVideoStatus());
            item.put("videoPrompt", scene.getVideoPrompt());
            item.put("imagePrompt", scene.getImagePrompt());
            item.put("imageModel", scene.getImageModel());
            item.put("imageSize", scene.getImageSize());
            item.put("imageQuality", scene.getImageQuality());
            item.put("videoModel", scene.getVideoModel());
            item.put("videoAspectRatio", scene.getVideoAspectRatio());
            item.put("videoResolution", scene.getVideoResolution());
            item.put("duration", scene.getDuration());
            item.put("negativePrompt", scene.getNegativePrompt());
            out.add(item);
        }
        return out;
    }

    private static List<String> imageUrls(Scene scene) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (scene.getImageUrls() != null && !scene.getImageUrls().isBlank()) {
            Arrays.stream(scene.getImageUrls().split(","))
                    .map(String::trim)
                    .filter(url -> !url.isBlank())
                    .forEach(urls::add);
        }
        if (scene.getImageUrl() != null && !scene.getImageUrl().isBlank()) urls.add(scene.getImageUrl());
        return List.copyOf(urls);
    }
}
