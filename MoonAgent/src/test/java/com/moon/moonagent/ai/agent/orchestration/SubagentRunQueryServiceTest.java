package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.dto.response.SubagentRunView;
import com.moon.moonagent.entity.AgentSubagentRun;
import com.moon.moonagent.mapper.AgentSubagentRunMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubagentRunQueryServiceTest {

    @Test
    void mapsAuditRowsToViews() {
        AgentSubagentRunMapper mapper = mock(AgentSubagentRunMapper.class);
        AgentSubagentRun run = new AgentSubagentRun();
        run.setId("g1:script");
        run.setGraphId("g1");
        run.setConversationId("c1");
        run.setNodeId("script");
        run.setRole("screenwriter");
        run.setStatus("COMPLETED");
        run.setTokens(100);
        run.setDurationMs(200L);
        when(mapper.selectList(any())).thenReturn(List.of(run));

        List<SubagentRunView> views = new SubagentRunQueryService(mapper)
                .list("c1", "g1", 50);

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo("g1:script");
            assertThat(view.role()).isEqualTo("screenwriter");
            assertThat(view.tokens()).isEqualTo(100);
            assertThat(view.durationMs()).isEqualTo(200L);
        });
    }

    @Test
    void retryPromptUsesStoredTaskSnapshot() {
        AgentSubagentRunMapper mapper = mock(AgentSubagentRunMapper.class);
        AgentSubagentRun run = new AgentSubagentRun();
        run.setId("g1:script");
        run.setGraphId("g1");
        run.setConversationId("c1");
        run.setNodeId("script");
        run.setRole("screenwriter");
        run.setStatus("FAILED");
        run.setTaskJson("{\"id\":\"script\",\"role\":\"screenwriter\",\"instruction\":\"写脚本\",\"tools\":[],\"maxTurns\":3,\"allowWrite\":false}");
        when(mapper.selectById("g1:script")).thenReturn(run);

        var prompt = new SubagentRunQueryService(mapper).retryPrompt("g1:script", "c1");

        assertThat(prompt.get("tool")).isEqualTo("run_task_graph");
        assertThat(prompt.get("prompt").toString())
                .contains("run_task_graph")
                .contains("写脚本")
                .contains("g1");
    }

    @Test
    void retryPromptRejectsForeignConversation() {
        AgentSubagentRunMapper mapper = mock(AgentSubagentRunMapper.class);
        AgentSubagentRun run = new AgentSubagentRun();
        run.setConversationId("c1");
        when(mapper.selectById("g1:script")).thenReturn(run);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SubagentRunQueryService(mapper).retryPrompt("g1:script", "other"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
