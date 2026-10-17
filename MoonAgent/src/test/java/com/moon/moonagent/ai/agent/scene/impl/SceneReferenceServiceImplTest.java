package com.moon.moonagent.ai.agent.scene.impl;

import com.moon.moonagent.ai.agent.scene.SceneReferenceView;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.request.AgentReferenceRequest;
import com.moon.moonagent.entity.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 分镜引用解析：归属过滤、字段口径、失败降级（绝不抛给上层）。 */
class SceneReferenceServiceImplTest {

    private final StoryboardClient storyboardClient = mock(StoryboardClient.class);

    @Test
    void resolvesOwnedSceneWithRealFields() {
        when(storyboardClient.getScene("scene-3")).thenReturn(scene("scene-3", "project-1"));
        SceneReferenceServiceImpl service = new SceneReferenceServiceImpl(storyboardClient);

        List<SceneReferenceView> views = service.resolve("project-1", List.of(reference("@分镜3", "scene-3")));

        assertThat(views).hasSize(1);
        SceneReferenceView view = views.getFirst();
        assertThat(view.resolved()).isTrue();
        assertThat(view.sceneNumber()).isEqualTo(3);
        assertThat(view.reason()).isEmpty();
        assertThat(view.fields())
                .containsEntry("剧情", "雨夜街头")
                .containsEntry("图片提示词", "霓虹特写")
                .containsEntry("运镜", "缓推")
                .doesNotContainKey("负面词")
                .doesNotContainKey("声音");
        assertThat(view.hasImage()).isTrue();
        assertThat(view.hasVideo()).isFalse();
    }

    @Test
    void sceneFromAnotherProjectIsUnresolved() {
        when(storyboardClient.getScene("scene-9")).thenReturn(scene("scene-9", "project-other"));
        SceneReferenceServiceImpl service = new SceneReferenceServiceImpl(storyboardClient);

        SceneReferenceView view = service
                .resolve("project-1", List.of(reference("@分镜9", "scene-9")))
                .getFirst();

        assertThat(view.resolved()).isFalse();
        assertThat(view.reason()).contains("不属于当前项目");
        assertThat(view.fields()).isEmpty();
    }

    @Test
    void deletedSceneAndBackendFailureDegradeInsteadOfThrowing() {
        when(storyboardClient.getScene("scene-deleted")).thenReturn(null);
        when(storyboardClient.getScene("scene-broken"))
                .thenThrow(new RuntimeException("主后端不可用"));
        SceneReferenceServiceImpl service = new SceneReferenceServiceImpl(storyboardClient);

        assertThatCode(() -> {
            SceneReferenceView deleted = service
                    .resolve("project-1", List.of(reference("@分镜1", "scene-deleted")))
                    .getFirst();
            assertThat(deleted.resolved()).isFalse();
            assertThat(deleted.reason()).contains("没有找到该分镜");

            SceneReferenceView broken = service
                    .resolve("project-1", List.of(reference("@分镜2", "scene-broken")))
                    .getFirst();
            assertThat(broken.resolved()).isFalse();
            assertThat(broken.reason()).contains("读取失败");
        }).doesNotThrowAnyException();
    }

    @Test
    void nonSceneReferencesAndMissingProjectAreIgnored() {
        SceneReferenceServiceImpl service = new SceneReferenceServiceImpl(storyboardClient);

        assertThat(service.resolve("project-1", List.of(
                new AgentReferenceRequest("conversation", "c9", "@会话", "会话",
                        null, null, null, null, null, null)))).isEmpty();
        assertThat(service.resolve("project-1", null)).isEmpty();

        SceneReferenceView noProject = service
                .resolve("", List.of(reference("@分镜3", "scene-3")))
                .getFirst();
        assertThat(noProject.resolved()).isFalse();
        assertThat(noProject.reason()).contains("没有项目归属");
    }

    private static AgentReferenceRequest reference(String token, String sceneId) {
        return new AgentReferenceRequest("scene", sceneId, token, token.replace("@", ""),
                null, null, null, null, null, 3);
    }

    private static Scene scene(String id, String projectId) {
        Scene scene = new Scene();
        scene.setId(id);
        scene.setProjectId(projectId);
        scene.setSceneNumber(3);
        scene.setScriptContent("雨夜街头");
        scene.setImagePrompt("霓虹特写");
        scene.setCameraMovement("缓推");
        scene.setImageUrl("/api/files/images/a.png");
        return scene;
    }
}
