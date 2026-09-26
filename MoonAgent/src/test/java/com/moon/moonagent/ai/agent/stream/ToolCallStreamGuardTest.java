package com.moon.moonagent.ai.agent.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallStreamGuardTest {

    @Test
    void removesLiteralToolCallMarkupAcrossChunks() {
        ToolCallStreamGuard guard = new ToolCallStreamGuard();

        String first = guard.accept("我来生成。<tool_call><function=generate_image_batch>");
        String middle = guard.accept("<parameter=images>[{\"prompt\":\"篮球\"}]</parameter>");
        String last = guard.accept("</function></tool_call>完成后告诉你。");

        assertThat(first).isEqualTo("我来生成。");
        assertThat(middle).isEmpty();
        assertThat(last).isEqualTo("完成后告诉你。");
        assertThat(guard.finish()).isEmpty();
        assertThat(guard.totalToolCalls()).isEqualTo(1);
    }

    @Test
    void normalTextIsPreserved() {
        ToolCallStreamGuard guard = new ToolCallStreamGuard();

        assertThat(guard.accept("好的，我现在开始生成图片。")).isEqualTo("好的，我现在开始生成图片。");
        assertThat(guard.finish()).isEmpty();
        assertThat(guard.totalToolCalls()).isZero();
    }

    @Test
    void repeatedIdenticalToolCallAbortsTurn() {
        ToolCallStreamGuard guard = new ToolCallStreamGuard();
        String block = "<tool_call><function=canvas_get_context></function></tool_call>";

        assertThatThrownBy(() -> {
            for (int i = 0; i < 10; i++) guard.accept(block);
        }).isInstanceOf(ToolCallStreamGuard.ToolCallLoopException.class)
                .hasMessageContaining("重复输出");
    }

    @Test
    void cyclingToolCallArgumentsAbortTurnInsteadOfLoopingForever() {
        // 复现线上记录：模型在 video / text2video / image2video 三个参数间来回打转。
        ToolCallStreamGuard guard = new ToolCallStreamGuard();
        String[] modes = {"videoGeneration", "video", "text2video", "image2video"};

        assertThatThrownBy(() -> {
            for (int i = 0; i < 200; i++) {
                guard.accept("<tool_call><function=get_compatible_models><parameter=nodeType>"
                        + modes[i % modes.length] + "</parameter></function></tool_call>");
            }
        }).isInstanceOf(ToolCallStreamGuard.ToolCallLoopException.class);

        assertThat(guard.totalToolCalls())
                .isLessThan(ToolCallStreamGuard.MAX_TOTAL_CALLS)
                .isLessThan(200);
    }

    @Test
    void unmatchedMarkupIsNeverShownToUser() {
        ToolCallStreamGuard guard = new ToolCallStreamGuard();

        assertThat(guard.accept("正在处理")).isEqualTo("正在处理");
        assertThat(guard.accept("<tool_call><function=get_canvas_nodes>")).isEmpty();
        assertThat(guard.accept("<parameter=x>1</parameter></function>")).isEmpty();
        assertThatThrownBy(() -> {
            for (int i = 0; i < 10; i++) {
                guard.accept("<tool_call><function=get_canvas_nodes></function></tool_call>");
            }
        }).isInstanceOf(ToolCallStreamGuard.ToolCallLoopException.class);
    }

    @Test
    void sanitizeCompleteStripsMarkupFromWholeText() {
        String raw = "已经完成。<tool_call><function=get_canvas_context></function></tool_call>请查看画布。";

        assertThat(ToolCallStreamGuard.sanitizeComplete(raw)).isEqualTo("已经完成。请查看画布。");
        assertThat(ToolCallStreamGuard.sanitizeComplete(null)).isEmpty();
    }
}
