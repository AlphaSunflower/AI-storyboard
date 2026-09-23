package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallTextFilterTest {

    @Test
    void removesLiteralToolCallMarkupAcrossChunks() {
        ToolCallTextFilter filter = new ToolCallTextFilter();

        String first = filter.accept("我来生成。<tool_call><function=generate_image_batch>");
        String middle = filter.accept("<parameter=images>[{\"prompt\":\"篮球\"}]</parameter>");
        String last = filter.accept("</function></tool_call>完成后告诉你。");

        assertThat(first).isEqualTo("我来生成。");
        assertThat(middle).isEmpty();
        assertThat(last).isEqualTo("完成后告诉你。");
        assertThat(filter.finish()).isEmpty();
    }

    @Test
    void normalTextIsPreserved() {
        ToolCallTextFilter filter = new ToolCallTextFilter();

        assertThat(filter.accept("好的，我现在开始生成图片。")).isEqualTo("好的，我现在开始生成图片。");
        assertThat(filter.finish()).isEmpty();
    }
}
