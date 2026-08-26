package com.moon.xiaoyuecanvas.config;

import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.ai.SceneView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 画布(无分镜)场景桥——满足 agent-core 生成服务对 {@link SceneGenerationBridge} 的构造依赖。
 *
 * <p>Morrow 画布没有分镜 {@code Scene}：所有生成调用 {@code sceneId=null}，从不真正调用桥方法，
 * 故用 no-op 实现兜底（getScene → null / updateScene → 无操作 / 资产注入 → 空）。
 * 若后续画布需要基于节点回写，可换成真实实现。
 *
 * <p>只在 XiaoYueCanvas 内装配；MoonAgent 仍用其 {@code SceneGenerationBridgeImpl}（真实读写分镜场景）。
 */
@Component
public class NoopSceneGenerationBridge implements SceneGenerationBridge {

    @Override
    public SceneView getScene(String sceneId) {
        return null;
    }

    @Override
    public SceneView getSceneByVideoTaskId(String taskId) {
        return null;
    }

    @Override
    public void updateScene(SceneView view) {
        // no-op（画布无分镜场景）
    }

    @Override
    public String sceneAssetSheet(String sceneId, String type) {
        return "";
    }

    @Override
    public List<String> sceneReferenceImages(String sceneId, String type) {
        return List.of();
    }
}
