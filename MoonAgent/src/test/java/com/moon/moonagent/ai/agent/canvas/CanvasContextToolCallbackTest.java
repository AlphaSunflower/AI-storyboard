package com.moon.moonagent.ai.agent.canvas;

import com.moon.moonagent.ai.agent.CanvasSessionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanvasContextToolCallbackTest {

    @Test
    void refreshesActiveCanvasFromUnscopedCanvasGetContextResult() throws Exception {
        ToolCallback getContext = mock(ToolCallback.class);
        when(getContext.getToolDefinition()).thenReturn(definition("canvas_get_context"));
        when(getContext.call(anyString())).thenReturn("""
                [{"type":"text","text":"{\\"projectId\\":\\"project-1\\",\\"canvasId\\":\\"current-canvas\\"}"}]
                """);

        ToolCallback getState = mock(ToolCallback.class);
        when(getState.getToolDefinition()).thenReturn(definition("canvas_get_state"));
        when(getState.call(anyString())).thenReturn("{\"ok\":true}");

        CanvasSessionContext context = new CanvasSessionContext(
                CanvasSessionContext.EDIT_MODE, "project-1", "stale-canvas", List.of());
        AtomicReference<String> activeCanvasId = new AtomicReference<>("stale-canvas");

        new CanvasContextToolCallback(getContext, context, activeCanvasId)
                .call("{\"projectId\":\"stale-canvas\",\"canvasId\":\"stale-canvas\",\"includeEdges\":true}");

        assertThat(activeCanvasId).hasValue("current-canvas");
        verify(getContext).call("{\"includeEdges\":true}");

        new CanvasContextToolCallback(getState, context, activeCanvasId).call("{}");
        verify(getState).call("{\"projectId\":\"project-1\",\"canvasId\":\"current-canvas\"}");
    }

    private static ToolDefinition definition(String name) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public String inputSchema() {
                return "{}";
            }
        };
    }
}
