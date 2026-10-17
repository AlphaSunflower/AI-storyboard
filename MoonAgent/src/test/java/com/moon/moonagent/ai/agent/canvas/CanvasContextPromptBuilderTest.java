package com.moon.moonagent.ai.agent.canvas;

import com.moon.moonagent.ai.agent.CanvasMediaRef;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanvasContextPromptBuilderTest {

    @Test
    void includesScopeAndCanvasFirstPriorityForCanvasTools() {
        CanvasSessionContext context = new CanvasSessionContext(
                "canvas-edit",
                "project-1",
                "canvas-1",
                List.of(
                        new CanvasMediaRef("image", null, "/api/files/images/ref.png", "ref.png", "image/png", "upload"),
                        new CanvasMediaRef("video", "video-1", "/api/files/videos/ref.mp4", "ref.mp4", "video/mp4", "upload")
                )
        );

        String prompt = CanvasContextPromptBuilder.build(context);

        assertThat(prompt)
                .contains("projectId=project-1")
                .contains("canvasId=canvas-1")
                // 画布编辑模式已有目标画布：不得再建任务、不得新建画布
                .contains("不要调用 canvas_create_run")
                .contains("imageGeneration")
                .contains("不要传这些内部字段")
                .contains("canvas_graph_import_media_batch")
                .contains("生图、生视频工具保留")
                .contains("分镜技能与分镜工具在画布模式下不加载")
                .contains("禁止向用户展示或要求输入")
                .doesNotContain("不得切换到分镜、图片、视频或通用回答链路");
    }

    @Test
    void returnsEmptyPromptOutsideCanvasEditMode() {
        assertThat(CanvasContextPromptBuilder.build(null)).isEmpty();
        assertThat(CanvasContextPromptBuilder.build(new CanvasSessionContext(
                "natural", "project-1", "canvas-1", List.of()))).isEmpty();
    }
}
