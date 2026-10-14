package com.moon.moonagent.ai.agent.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentConversation;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用草稿累积：后端落库 `metadata.toolDrafts` 后，刷新/重放才能继续渲染工具明细。
 *
 * <p>字段语义必须与前端 `generating.toolDrafts` 完全一致，否则直播与刷新两条路径会渲染成两种样子。
 */
class AgentTraceAccumulatorToolDraftsTest {

    private static AgentTraceAccumulator accumulator() {
        return new AgentTraceAccumulator(new OrchestrationRequest(
                new AgentConversation(), "hello", List.of(), new SseEmitter()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> draftOf(AgentTraceAccumulator trace, String toolCallId) {
        return (Map<String, Object>) trace.toolDrafts().get(toolCallId);
    }

    @Test
    void accumulatesInputTextAndStatusAcrossOneToolLifecycle() {
        AgentTraceAccumulator trace = accumulator();

        trace.recordToolEvent(new ToolCallStartEvent("reply-1", "call-1", "generate_image"));
        trace.recordToolEvent(new ToolCallDeltaEvent("reply-1", "call-1", "generate_image", "{\"prompt\":"));
        trace.recordToolEvent(new ToolCallDeltaEvent("reply-1", "call-1", "generate_image", "\"cat\"}"));
        trace.recordToolEvent(new ToolResultStartEvent("reply-1", "call-1", "generate_image"));
        trace.recordToolEvent(new ToolResultTextDeltaEvent("reply-1", "call-1", "generate_image", "{\"ok\":"));
        trace.recordToolEvent(new ToolResultTextDeltaEvent("reply-1", "call-1", "generate_image", "true}"));
        trace.recordToolEvent(new ToolResultEndEvent(
                "reply-1", "call-1", "generate_image", ToolResultState.SUCCESS));

        assertThat(trace.toolDrafts()).containsOnlyKeys("call-1");
        Map<String, Object> draft = draftOf(trace, "call-1");
        assertThat(draft)
                .containsEntry("name", "generate_image")
                .containsEntry("input", "{\"prompt\":\"cat\"}")
                .containsEntry("text", "{\"ok\":true}")
                .containsEntry("status", "node_completed");
        assertThat((Long) draft.get("startedAt")).isPositive();
    }

    @Test
    void errorStateIsRecordedAsFailedNotCompleted() {
        AgentTraceAccumulator trace = accumulator();

        trace.recordToolEvent(new ToolCallStartEvent("reply-1", "call-1", "run_python_script"));
        trace.recordToolEvent(new ToolResultEndEvent(
                "reply-1", "call-1", "run_python_script", ToolResultState.ERROR));

        // ToolResultState 的取值是 SUCCESS/ERROR/INTERRUPTED/DENIED/RUNNING：
        // 只判 FAIL/DENIED 会把 ERROR 记成"已完成"。
        assertThat(draftOf(trace, "call-1")).containsEntry("status", "node_failed");
    }

    @Test
    void deniedAndInterruptedAlsoCountAsFailed() {
        AgentTraceAccumulator trace = accumulator();

        trace.recordToolEvent(new ToolCallStartEvent("reply-1", "denied-1", "write_file"));
        trace.recordToolEvent(new ToolResultEndEvent(
                "reply-1", "denied-1", "write_file", ToolResultState.DENIED));
        trace.recordToolEvent(new ToolCallStartEvent("reply-1", "stopped-1", "run_python_script"));
        trace.recordToolEvent(new ToolResultEndEvent(
                "reply-1", "stopped-1", "run_python_script", ToolResultState.INTERRUPTED));

        assertThat(draftOf(trace, "denied-1")).containsEntry("status", "node_failed");
        assertThat(draftOf(trace, "stopped-1")).containsEntry("status", "node_failed");
    }

    @Test
    void keepsFirstSeenOrderSoReplayMatchesLive() {
        AgentTraceAccumulator trace = accumulator();

        trace.recordToolEvent(new ToolCallStartEvent("reply-1", "call-b", "second_tool"));
        trace.recordToolEvent(new ToolCallStartEvent("reply-1", "call-a", "first_tool"));
        trace.recordToolEvent(new ToolCallStartEvent("reply-1", "call-b", "second_tool"));

        // 前端 orderedToolDrafts 缺 seq 时回落到插入顺序，因此这里必须保持首次出现顺序
        assertThat(new ArrayList<>(trace.toolDrafts().keySet()))
                .containsExactly("call-b", "call-a");
    }

    @Test
    void noToolEventsMeansNoMetadataKey() {
        AgentTraceAccumulator trace = accumulator();

        trace.recordToolEvent(new io.agentscope.core.event.ThinkingBlockStartEvent("reply-1", "think-1"));

        // 调用方据此不写 metadata.toolDrafts，既有 payload 逐字节不变（零回归）
        assertThat(trace.toolDrafts()).isEmpty();
    }

    @Test
    void toolDraftsSurviveJsonRoundTrip() throws Exception {
        AgentTraceAccumulator trace = accumulator();
        trace.recordToolEvent(new ToolCallStartEvent("reply-1", "call-1", "generate_image"));
        trace.recordToolEvent(new ToolResultTextDeltaEvent("reply-1", "call-1", "generate_image", "完成"));
        trace.recordToolEvent(new ToolResultEndEvent(
                "reply-1", "call-1", "generate_image", ToolResultState.SUCCESS));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(Map.of("toolDrafts", trace.toolDrafts()));
        Map<String, Object> restored = mapper.readValue(json, new TypeReference<>() { });

        assertThat(restored).containsKey("toolDrafts");
        @SuppressWarnings("unchecked")
        Map<String, Object> drafts = (Map<String, Object>) restored.get("toolDrafts");
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) drafts.get("call-1");
        assertThat(draft)
                .containsEntry("name", "generate_image")
                .containsEntry("text", "完成")
                .containsEntry("status", "node_completed");
    }
}
