package com.moon.moonagent.service.impl;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.request.StoryboardImageEnqueueItem;
import com.moon.moonagent.dto.request.StoryboardImageEnqueueRequest;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.service.AssetService;
import com.moon.moonagent.service.StoryboardAutoImageService;
import com.moon.moonagent.service.StoryboardImageQueueService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分镜自动配图：只处理无图、无参考素材、无关联资产的目标分镜。
 */
@Service
@RequiredArgsConstructor
public class StoryboardAutoImageServiceImpl implements StoryboardAutoImageService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardAutoImageServiceImpl.class);

    private final StoryboardClient storyboardClient;
    private final AssetService assetService;
    private final StoryboardImageQueueService imageQueueService;

    @Override
    public int enqueueMissingMaterialImages(String conversationId, String projectId,
                                            List<Integer> sceneNumbers) {
        if (isBlank(conversationId) || isBlank(projectId) || sceneNumbers == null || sceneNumbers.isEmpty()) {
            return 0;
        }
        Set<Integer> targets = new HashSet<>(sceneNumbers);
        List<Scene> candidates = storyboardClient.getProjectScenes(projectId).stream()
                .filter(scene -> targets.contains(scene.getSceneNumber()))
                .filter(scene -> scene != null
                        && scene.getId() != null
                        && !scene.getId().isBlank()
                        && isBlank(scene.getImageUrl())
                        && isBlank(scene.getImageUrls()))
                .toList();
        Map<String, Map<String, Boolean>> presence;
        try {
            presence = storyboardClient.sceneMaterialPresence(
                    projectId, candidates.stream().map(Scene::getId).toList());
        } catch (Exception e) {
            log.warn("[分镜自动配图] 批量素材状态读取失败，回退逐场景判断: {}", e.getMessage());
            presence = Map.of();
        }
        final Map<String, Map<String, Boolean>> materialPresence = presence;
        List<StoryboardImageEnqueueItem> items = candidates.stream()
                .filter(scene -> isEligible(scene, materialPresence))
                .map(this::toEnqueueItem)
                .toList();
        if (items.isEmpty()) return 0;

        imageQueueService.enqueue(new StoryboardImageEnqueueRequest(conversationId, projectId, items));
        log.info("[分镜自动配图] 已入队 {} 个分镜: projectId={}, sceneNumbers={}",
                items.size(), projectId, items.stream().map(StoryboardImageEnqueueItem::sceneId).toList());
        return items.size();
    }

    private boolean isEligible(Scene scene) {
        return isEligible(scene, Map.of());
    }

    private boolean isEligible(Scene scene, Map<String, Map<String, Boolean>> presence) {
        if (scene == null || scene.getId() == null || scene.getId().isBlank()) return false;
        if (!isBlank(scene.getImageUrl()) || !isBlank(scene.getImageUrls())) return false;
        Map<String, Boolean> material = presence.get(scene.getId());
        if (material != null) {
            return !Boolean.TRUE.equals(material.get("hasReferences"))
                    && !Boolean.TRUE.equals(material.get("hasAssets"));
        }
        try {
            boolean hasReferences = !storyboardClient.getSceneReferences(scene.getId()).isEmpty();
            if (hasReferences) return false;
            return assetService.sceneAssets(scene.getId(), null).isEmpty();
        } catch (Exception e) {
            log.warn("[分镜自动配图] 素材状态读取失败，保守跳过: sceneId={}, error={}",
                    scene.getId(), e.getMessage());
            return false;
        }
    }

    private StoryboardImageEnqueueItem toEnqueueItem(Scene scene) {
        String prompt = !isBlank(scene.getImagePrompt())
                ? scene.getImagePrompt()
                : (!isBlank(scene.getScriptContent())
                ? scene.getScriptContent()
                : "分镜" + scene.getSceneNumber() + "画面");
        return new StoryboardImageEnqueueItem(
                scene.getId(), prompt, null, null, null, 1,
                null, List.of(), "generate");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
