package com.moon.moonagent.ai.agent.scene.impl;

import com.moon.moonagent.ai.agent.scene.SceneReferenceService;
import com.moon.moonagent.ai.agent.scene.SceneReferenceView;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.request.AgentReferenceRequest;
import com.moon.moonagent.entity.Scene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SceneReferenceService} 实现：单镜读取 + 项目归属过滤。
 *
 * <p>复用 {@link StoryboardClient#getScene(String)}（带 30s 缓存）。任何异常都在此收敛成
 * {@code resolved=false}，绝不打断本轮对话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneReferenceServiceImpl implements SceneReferenceService {

    /** 注入提示词用的中文字段标签 → Scene 取值；顺序即注入顺序（与设计方案 §4.3 一致）。 */
    private static final Map<String, java.util.function.Function<Scene, String>> FIELD_READERS =
            fieldReaders();

    private final StoryboardClient storyboardClient;

    @Override
    public List<SceneReferenceView> resolve(String projectId,
                                            List<AgentReferenceRequest> references) {
        if (references == null || references.isEmpty()) return List.of();
        List<SceneReferenceView> views = new ArrayList<>();
        for (AgentReferenceRequest reference : references) {
            if (reference == null || !"scene".equalsIgnoreCase(reference.kind())) continue;
            views.add(resolveOne(projectId, reference));
        }
        return List.copyOf(views);
    }

    /** 解析单条分镜引用，失败一律降级为未解析结果。 */
    private SceneReferenceView resolveOne(String projectId, AgentReferenceRequest reference) {
        String token = reference.token() == null ? "" : reference.token();
        String sceneId = reference.id() == null ? "" : reference.id();
        if (sceneId.isBlank()) {
            return unresolved(token, sceneId, "引用没有携带分镜 ID，请向用户确认要引用的分镜");
        }
        if (projectId == null || projectId.isBlank()) {
            return unresolved(token, sceneId, "当前会话没有项目归属，无法读取该分镜");
        }
        Scene scene;
        try {
            scene = storyboardClient.getScene(sceneId);
        } catch (RuntimeException error) {
            log.warn("[Reference] 分镜读取失败 sceneId={} projectId={}: {}",
                    sceneId, projectId, error.getMessage());
            return unresolved(token, sceneId, "分镜读取失败（主后端不可用），请稍后再试或向用户确认");
        }
        if (scene == null) {
            return unresolved(token, sceneId, "没有找到该分镜，请向用户确认");
        }
        if (scene.getProjectId() == null || !projectId.equals(scene.getProjectId())) {
            // 越权（引用别的项目 / 别的用户的分镜）：不入上下文，只记日志
            log.warn("[Reference] 分镜不属于当前项目 sceneId={} sceneProjectId={} sessionProjectId={}",
                    sceneId, scene.getProjectId(), projectId);
            return unresolved(token, sceneId, "该分镜不属于当前项目，未注入内容，请向用户确认");
        }
        return new SceneReferenceView(
                token,
                scene.getId() == null ? sceneId : scene.getId(),
                scene.getSceneNumber(),
                true,
                "",
                fields(scene),
                hasImage(scene),
                hasVideo(scene));
    }

    private static SceneReferenceView unresolved(String token, String sceneId, String reason) {
        return new SceneReferenceView(token, sceneId, null, false, reason, Map.of(), false, false);
    }

    /** 只收非空字段，保持注入顺序稳定。 */
    private static Map<String, String> fields(Scene scene) {
        Map<String, String> values = new LinkedHashMap<>();
        FIELD_READERS.forEach((label, reader) -> {
            String value = reader.apply(scene);
            if (value != null && !value.isBlank()) values.put(label, value.strip());
        });
        // 必须保留插入顺序（注入时的字段顺序即设计稿顺序）：Map.copyOf 不保证顺序
        return java.util.Collections.unmodifiableMap(values);
    }

    private static Map<String, java.util.function.Function<Scene, String>> fieldReaders() {
        Map<String, java.util.function.Function<Scene, String>> readers = new LinkedHashMap<>();
        readers.put("剧情", Scene::getScriptContent);
        readers.put("图片提示词", Scene::getImagePrompt);
        readers.put("视频提示词", Scene::getVideoPrompt);
        readers.put("负面词", Scene::getNegativePrompt);
        readers.put("运镜", Scene::getCameraMovement);
        readers.put("景别", Scene::getShotType);
        readers.put("声音", Scene::getSoundDesign);
        return java.util.Collections.unmodifiableMap(readers);
    }

    private static boolean hasImage(Scene scene) {
        if (scene.getImageUrl() != null && !scene.getImageUrl().isBlank()) return true;
        return scene.getImageUrls() != null && !scene.getImageUrls().isBlank();
    }

    private static boolean hasVideo(Scene scene) {
        return scene.getVideoUrl() != null && !scene.getVideoUrl().isBlank();
    }
}
