package com.moon.moonagent.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanvasSessionContextTest {

    @Test
    void jsonCodecOmitsDerivedMethodsAndReadsLegacyFields() {
        CanvasSessionContext context = new CanvasSessionContext(
                "canvas-edit", "project-1", "canvas-1", List.of());

        String json = context.toJson();

        assertThat(json).doesNotContain("canvasEditMode");
        assertThat(CanvasSessionContext.fromJson(json).isCanvasEditMode()).isTrue();
        assertThat(CanvasSessionContext.fromJson(
                "{\"mode\":\"canvas-edit\",\"projectId\":\"project-1\",\"canvasId\":\"canvas-1\","
                        + "\"mediaRefs\":[],\"canvasEditMode\":true}").isCanvasEditMode()).isTrue();
    }

    @Test
    void matchesExcludedMediaByUrlPath() {
        CanvasSessionContext context = new CanvasSessionContext(
                "canvas-edit", "project-1", "canvas-1", List.of(),
                List.of("http://localhost:5173/api/files/images/removed.jpg"));

        assertThat(context.isMediaExcluded("/api/files/images/removed.jpg")).isTrue();
        assertThat(context.isMediaExcluded("http://localhost:3000/api/files/images/removed.jpg")).isTrue();
        assertThat(context.isMediaExcluded("/api/files/images/kept.jpg")).isFalse();
    }
}
