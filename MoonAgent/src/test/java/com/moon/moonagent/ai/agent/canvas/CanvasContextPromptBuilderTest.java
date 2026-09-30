package com.moon.moonagent.ai.agent.canvas;

import com.moon.moonagent.ai.agent.CanvasMediaRef;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanvasContextPromptBuilderTest {

    @Test
    void includesScopeAndMediaReferencesForCanvasTools() {
        CanvasSessionContext context = new CanvasSessionContext(
                "canvas-edit",
                "project-1",
                "canvas-1",
                List.of(
                        new CanvasMediaRef("image", null, "/api/files/images/ref.png", "ref.png", "image/png", "upload"),
                        new CanvasMediaRef("video", "video-1", "/api/files/videos/ref.mp4", "ref.mp4", "video/mp4", "upload")
                )
        );

        String prompt = CanvasContextPromptBuilder.build(context, List.of());

        assertThat(prompt)
                .contains("projectId=project-1")
                .contains("canvasId=canvas-1")
                .contains("request_human_input")
                .contains("禁止向用户展示或要求输入")
                .contains("image")
                .contains("/api/files/images/ref.png")
                .contains("video-1");
    }

    @Test
    void returnsEmptyPromptOutsideCanvasEditMode() {
        assertThat(CanvasContextPromptBuilder.build(null, List.of())).isEmpty();
    }

    @Test
    void exposesUploadedImagesEvenWhenCanvasContextIsMissing() {
        String prompt = CanvasContextPromptBuilder.build(null, List.of(
                "http://localhost:5173/api/files/images/one.png",
                "http://localhost:5173/api/files/images/two.png"
        ));

        assertThat(prompt)
                .contains("本轮上传媒体")
                .contains("one.png")
                .contains("two.png")
                .contains("canvas_import_media");
    }
}
