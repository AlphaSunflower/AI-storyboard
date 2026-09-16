package com.moon.moonagent.ai.agent.canvas;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CanvasToolProgressCallbackTest {

    @Test
    void emitsHumanReadableProgressBeforeToolExecution() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("canvas_import_media")
                        .description("import")
                        .inputSchema("{}")
                        .build());
        List<String> progress = new ArrayList<>();

        new CanvasToolProgressCallback(delegate, progress::add)
                .call("{\"kind\":\"image\",\"url\":\"/api/files/images/a.jpg\"}");

        assertThat(progress).containsExactly("正在导入图片");
    }

    @Test
    void includesBatchCountForResultSlotCreation() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("canvas_create_result_slots")
                        .description("slots")
                        .inputSchema("{}")
                        .build());
        List<String> progress = new ArrayList<>();

        new CanvasToolProgressCallback(delegate, progress::add)
                .call("{\"kind\":\"image\",\"count\":3}");

        assertThat(progress).containsExactly("正在创建 3 个图片结果槽");
    }
}
