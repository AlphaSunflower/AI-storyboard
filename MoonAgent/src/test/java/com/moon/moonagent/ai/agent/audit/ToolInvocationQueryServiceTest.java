package com.moon.moonagent.ai.agent.audit;

import com.moon.moonagent.entity.AgentToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolInvocationQueryServiceTest {

    @Test
    void listAlwaysScopesToAuthenticatedUser() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation row = new AgentToolInvocation();
        row.setId("inv-1");
        row.setUserId("user-1");
        row.setToolName("refineImage");
        row.setStatus("COMPLETED");
        row.setStartedAt(java.time.OffsetDateTime.now());
        when(ledger.list(eq("user-1"), eq("task-1"), eq("conversation-1"), eq(null), eq(50)))
                .thenReturn(List.of(row));
        ToolInvocationQueryService service =
                new ToolInvocationQueryService(ledger, new ToolAuditProperties());

        Map<String, Object> result = service.list(
                "user-1", "task-1", "conversation-1", null, 50);

        assertThat(result).containsKey("records");
        assertThat((List<?>) result.get("records")).hasSize(1);
    }

    @Test
    void detailReturnsNullForUnownedInvocation() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        ToolInvocationQueryService service =
                new ToolInvocationQueryService(ledger, new ToolAuditProperties());

        assertThat(service.detail("user-1", "missing")).isNull();
    }
}
