package com.moon.moonagent.ai.agent.canvas;

import com.moon.moonagent.ai.agent.CanvasSessionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanvasModeIntentPolicyTest {

    @Test
    void keepsExplicitCanvasRunAndDeleteRules() {
        assertThat(CanvasModeIntentPolicy.resolve("执行整张画布", "intent-canvas-run"))
                .isEqualTo("intent-canvas-run");
        assertThat(CanvasModeIntentPolicy.resolve("删除节点", "intent-canvas-delete"))
                .isEqualTo("intent-canvas-delete");
    }

    @Test
    void defaultsAmbiguousCanvasCommandsToDesign() {
        assertThat(CanvasModeIntentPolicy.resolve("把这两个连起来", null))
                .isEqualTo("intent-canvas-design");
    }

    @Test
    void neverFallsThroughToANonCanvasIntent() {
        assertThat(CanvasModeIntentPolicy.resolve("生成一个视频", "intent-video"))
                .isEqualTo("intent-canvas-design");
    }

    @Test
    void requiresBindingForCanvasIntentWithoutContext() {
        assertThat(CanvasModeIntentPolicy.requiresBinding("intent-canvas-design", null)).isTrue();
        assertThat(CanvasModeIntentPolicy.requiresBinding("intent-canvas-run", null)).isTrue();
        assertThat(CanvasModeIntentPolicy.requiresBinding("intent-canvas-delete", null)).isTrue();
        assertThat(CanvasModeIntentPolicy.requiresBinding("intent-video", null)).isFalse();
    }

    @Test
    void existingCanvasContextDoesNotRequireBinding() {
        CanvasSessionContext context = new CanvasSessionContext(
                "canvas-edit", "project-1", "canvas-1", List.of());

        assertThat(CanvasModeIntentPolicy.requiresBinding("intent-canvas-design", context)).isFalse();
    }

    @Test
    void checkpointAndSubmittedBindingActionsAreDistinct() {
        assertThat(CanvasModeIntentPolicy.BIND_ACTION).isEqualTo("canvas-bind");
        assertThat(CanvasModeIntentPolicy.BOUND_ACTION).isEqualTo("canvas-bound");
    }
}
