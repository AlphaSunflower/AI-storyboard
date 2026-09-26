package com.moon.moonagent.ai.agent.audit;

import com.moon.moonagent.entity.AgentToolInvocation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutionTemplateTest {

    @Test
    void nullInputValuesDoNotBreakAuditPath() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-null");
        when(ledger.start(any(), any())).thenReturn(record);
        ToolExecutionTemplate template = new ToolExecutionTemplate(
                ledger,
                new ToolAuditSummarizer(),
                new ToolAuditProperties(),
                new SimpleMeterRegistry());
        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("conversationId", null);

        String result = template.execute("refineImage", "MEDIA_IMAGE", "SPRING_AI",
                input, () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void nestedSameToolReusesSingleInvocationRecord() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-1");
        when(ledger.start(any(), any())).thenReturn(record);
        ToolAuditProperties properties = new ToolAuditProperties();
        ToolExecutionTemplate template = new ToolExecutionTemplate(
                ledger,
                new ToolAuditSummarizer(),
                properties,
                new SimpleMeterRegistry());
        AtomicInteger calls = new AtomicInteger();

        String result = template.execute("refineImage", "MEDIA_IMAGE", "SPRING_AI",
                java.util.Map.of("prompt", "hello"),
                () -> {
                    calls.incrementAndGet();
                    return template.execute("refineImage", "MEDIA_IMAGE", "AGENT_SCOPE",
                            java.util.Map.of("prompt", "hello"),
                            () -> "generated");
                });

        assertThat(result).isEqualTo("generated");
        assertThat(calls).hasValue(1);
        verify(ledger, times(1)).start(any(), any());
        verify(ledger, times(1)).finish(any(), any());
    }
}
