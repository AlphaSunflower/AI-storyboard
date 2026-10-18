package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.orchestration.SubagentCancellation;
import com.moon.moonagent.ai.agent.orchestration.SubagentRoleCatalog;
import com.moon.moonagent.ai.agent.orchestration.SubagentTask;
import com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult;
import com.moon.moonagent.ai.agent.orchestration.SubagentTaskStatus;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubagentAgentExecutorTest {

    @Test
    void injectsSharedContextIntoEveryChildPrompt() {
        AtomicReference<String> seen = new AtomicReference<>("");
        Model model = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                seen.set(messages.stream()
                        .map(Msg::getTextContent)
                        .reduce("", (a, b) -> a + "\n" + b));
                return Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder()
                                .text("{\"summary\":\"ok\",\"output\":\"完成\"}")
                                .build()))
                        .build());
            }

            @Override
            public String getModelName() {
                return "fake-model";
            }

            @Override
            public int getContextWindowSize() {
                return 128_000;
            }
        };
        ReActAgent delegate = mock(ReActAgent.class);
        when(delegate.getModel()).thenReturn(model);
        HarnessAgent parent = mock(HarnessAgent.class);
        when(parent.getDelegate()).thenReturn(delegate);
        when(parent.getToolkit()).thenReturn(new Toolkit());

        SubagentAgentExecutor executor = new SubagentAgentExecutor(new SubagentRoleCatalog());
        executor.attach(parent);
        SubagentTask task = new SubagentTask(
                "script", "screenwriter", "写脚本", List.of(), List.of(), List.of(), 3);

        executor.execute(task, Map.of(), new SubagentCancellation(),
                "CANONICAL-CONTEXT-123");

        assertThat(seen.get()).contains("CANONICAL-CONTEXT-123");
    }

    @Test
    void buildsARealChildAgentAndParsesItsJsonResult() {
        Model model = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                return Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder()
                                .text("{\"summary\":\"ok\",\"output\":\"调研完成\"}")
                                .build()))
                        .build());
            }

            @Override
            public String getModelName() {
                return "fake-model";
            }

            @Override
            public int getContextWindowSize() {
                return 128_000;
            }
        };
        ReActAgent delegate = mock(ReActAgent.class);
        when(delegate.getModel()).thenReturn(model);
        HarnessAgent parent = mock(HarnessAgent.class);
        when(parent.getDelegate()).thenReturn(delegate);
        when(parent.getToolkit()).thenReturn(new Toolkit());

        SubagentAgentExecutor executor = new SubagentAgentExecutor(new SubagentRoleCatalog());
        executor.attach(parent);

        SubagentTask task = new SubagentTask(
                "research", "researcher", "梳理项目", List.of(), List.of(), List.of(), 4);
        SubagentTaskResult result = executor.execute(
                task, Map.of(), new SubagentCancellation());

        assertThat(result.status()).isEqualTo(SubagentTaskStatus.COMPLETED);
        assertThat(result.output()).isEqualTo("调研完成");
    }
}
