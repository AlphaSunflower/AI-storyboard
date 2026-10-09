package com.moon.moonagent.ai.agent.audit;

import com.moon.moonagent.mapper.AgentToolInvocationMapper;
import com.moon.moonagent.entity.AgentToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolInvocationLedgerTest {

    @Test
    void auditSchemaFailureDoesNotBlockToolExecution() {
        AgentToolInvocationMapper mapper = mock(AgentToolInvocationMapper.class);
        when(mapper.insert(any(AgentToolInvocation.class)))
                .thenThrow(new RuntimeException("relation \"agent_tool_invocations\" does not exist"));
        ToolAuditProperties properties = new ToolAuditProperties();
        properties.setEnabled(true);
        ToolInvocationLedger ledger = new ToolInvocationLedger(
                mapper, new ToolAuditSummarizer(), properties);
        ToolInvocationContext context = ToolInvocationContext.builder()
                .invocationKey("checkpoint:call:generate_video")
                .toolName("generate_video")
                .source("AGENT_SCOPE")
                .build();

        assertThatCode(() -> ledger.start(context, Map.of("prompt", "test")))
                .doesNotThrowAnyException();
        assertThat(ledger.start(context, Map.of("prompt", "test"))).isNull();
    }
}
