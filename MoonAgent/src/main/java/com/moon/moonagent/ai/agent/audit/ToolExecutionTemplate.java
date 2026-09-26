package com.moon.moonagent.ai.agent.audit;

import com.moon.moonagent.entity.AgentToolInvocation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutionTemplate {

    private final ToolInvocationLedger ledger;
    private final ToolAuditSummarizer summarizer;
    private final ToolAuditProperties properties;
    private final MeterRegistry meters;

    public <T> T execute(String toolName,
                         String toolKind,
                         String source,
                         Object input,
                         Supplier<T> invocation) {
        if (!properties.isEnabled()) return invocation.get();
        ToolInvocationScope active = ToolInvocationScopeHolder.current();
        if (active != null && toolName.equals(active.context().getToolName())) {
            return invocation.get();
        }
        AgentInvocationContext base = AgentInvocationContextHolder.current();
        ToolInvocationContext context = context(toolName, toolKind, source, input, base);
        AgentToolInvocation record = ledger.start(context, input);
        long started = System.nanoTime();
        try (ToolInvocationScopeHolder.Scope ignored =
                     ToolInvocationScopeHolder.open(new ToolInvocationScope(record, context,
                             OffsetDateTime.now()))) {
            try {
                T output = invocation.get();
                finish(record, context, input, output, null, started);
                return output;
            } catch (RuntimeException e) {
                finish(record, context, input, null, e, started);
                throw e;
            }
        }
    }

    public void finish(AgentToolInvocation record,
                       ToolInvocationContext context,
                       Object input,
                       Object output,
                       Throwable error,
                       long startedNanos) {
        try {
            ToolAuditSummarizer.ReferenceCounts counts = summarizer.referenceCounts(input, output);
            ToolInvocationResult result = ToolInvocationResult.builder()
                    .status(error == null ? summarizer.status(output) : "FAILED")
                    .outputHash(error == null ? summarizer.hash(output) : "")
                    .outputSummary(error == null ? summarizer.outputSummary(context.getToolName(), output) : "")
                    .errorCode(error == null ? summarizer.errorCode(output) : error.getClass().getSimpleName())
                    .errorMessage(error == null ? summarizer.errorMessage(output)
                            : ToolAuditSummarizer.truncate(error.getMessage(), 2000))
                    .providerTaskId(error == null ? summarizer.providerTaskId(output) : "")
                    .resultAssetIds(error == null ? summarizer.resultAssetIds(output) : List.of())
                    .expectedReferenceCount(counts.expectedReferenceCount())
                    .resolvedReferenceCount(counts.resolvedReferenceCount())
                    .appliedReferenceCount(counts.appliedReferenceCount())
                    .build();
            ledger.finish(record, result);
            metric(context, result.getStatus(), startedNanos);
        } catch (Exception metricError) {
            log.debug("tool audit finish failed tool={} error={}",
                    context.getToolName(), metricError.getMessage());
        }
    }

    private void metric(ToolInvocationContext context, String status, long startedNanos) {
        try {
            Timer.builder("agent_tool_invocation_duration")
                    .tag("tool", context.getToolName())
                    .tag("source", context.getSource())
                    .tag("status", status == null ? "unknown" : status)
                    .register(meters)
                    .record(Duration.ofNanos(System.nanoTime() - startedNanos));
            meters.counter("agent_tool_invocation_total",
                    "tool", context.getToolName(),
                    "source", context.getSource(),
                    "status", status == null ? "unknown" : status).increment();
        } catch (Exception e) {
            log.debug("tool audit metric failed tool={} error={}", context.getToolName(), e.getMessage());
        }
    }

    private static ToolInvocationContext context(String toolName,
                                                 String toolKind,
                                                 String source,
                                                 Object input,
                                                 AgentInvocationContext base) {
        Map<String, Object> values = input instanceof Map<?, ?> map
                ? stringKeyMap(map) : Map.of();
        String conversationId = first(base.conversationId(), values.get("conversationId"),
                values.get("conversation_id"));
        String projectId = first(base.projectId(), values.get("projectId"), values.get("project_id"));
        return ToolInvocationContext.builder()
                .traceId(base.traceId())
                .loopRunId(base.loopRunId())
                .loopStepId(base.loopStepId())
                .taskId(base.taskId())
                .conversationId(conversationId)
                .userId(base.userId())
                .projectId(projectId)
                .toolCallId("")
                .toolName(toolName)
                .toolKind(toolKind)
                .source(source)
                .skillVersion(base.skillVersion())
                .model(base.model())
                .engineVersion(base.engineVersion())
                .build();
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static String first(String base, Object... values) {
        if (base != null && !base.isBlank()) return base;
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }
}
