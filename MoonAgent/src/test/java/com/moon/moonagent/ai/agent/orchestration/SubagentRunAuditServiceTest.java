package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.entity.AgentSubagentRun;
import com.moon.moonagent.mapper.AgentSubagentRunMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubagentRunAuditServiceTest {

    @Test
    void startedInsertsRunningRow() {
        AgentSubagentRunMapper mapper = mock(AgentSubagentRunMapper.class);
        when(mapper.selectById(any())).thenReturn(null);
        SubagentRunAuditService service = new SubagentRunAuditService(mapper);

        String id = service.started("g1", "c1", "t1", "call-1",
                new SubagentTask("research", "researcher", "调研", List.of(),
                        List.of(), List.of(), 4));

        assertThat(id).isEqualTo("g1:research");
        verify(mapper).insert(any(AgentSubagentRun.class));
    }

    @Test
    void completedUpdatesExistingRow() {
        AgentSubagentRunMapper mapper = mock(AgentSubagentRunMapper.class);
        AgentSubagentRun existing = new AgentSubagentRun();
        existing.setId("g1:research");
        existing.setCreatedAt(java.time.OffsetDateTime.now());
        when(mapper.selectById("g1:research")).thenReturn(existing);
        SubagentRunAuditService service = new SubagentRunAuditService(mapper);

        service.completed("g1", "research",
                SubagentTaskResult.completed("research", "done", 120, 30));

        verify(mapper).updateById(existing);
        assertThat(existing.getStatus()).isEqualTo("COMPLETED");
        assertThat(existing.getOutput()).isEqualTo("done");
        assertThat(existing.getTokens()).isEqualTo(30);
    }
}
