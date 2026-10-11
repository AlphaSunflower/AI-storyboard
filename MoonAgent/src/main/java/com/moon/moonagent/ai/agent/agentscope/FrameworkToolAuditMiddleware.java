package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.audit.ToolAuditProperties;
import com.moon.moonagent.ai.agent.audit.ToolAuditSummarizer;
import com.moon.moonagent.ai.agent.audit.ToolInvocationContext;
import com.moon.moonagent.ai.agent.audit.ToolInvocationLedger;
import com.moon.moonagent.ai.agent.audit.ToolInvocationResult;
import com.moon.moonagent.ai.agent.audit.ToolInvocationScopeHolder;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentToolInvocation;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 为 Harness 直接注册的框架工具补齐工具审计。
 *
 * <p>{@code AgentScopeToolPipeline} 只包装自研工具，框架的 {@code FilesystemTool} /
 * {@code ShellExecuteTool} / {@code ArtifactDeliveryTool} 由 Harness 直接注册，绕过了审计管线。
 * 本中间件按工具名白名单旁观 {@code onActing} 事件流，用 {@link ToolInvocationLedger} 写入同一张
 * {@code agent_tool_invocations}，不改变任何工具行为。
 *
 * <p>白名单里的名字在自研沙箱工具退役后已由框架独占，加上 {@link ToolInvocationScopeHolder}
 * 同名守卫，不会与管线审计重复计数。
 */
@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled", havingValue = "true")
final class FrameworkToolAuditMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(FrameworkToolAuditMiddleware.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_OUTPUT_CHARS = 4000;

    /** 框架注册的工具名（自研沙箱工具退役后这些名字不再由管线提供）。 */
    static final Set<String> AUDITED_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "grep_files", "glob_files",
            "list_files", "execute", "deliver_artifact");

    private final ToolInvocationLedger ledger;
    private final ToolAuditSummarizer summarizer;
    private final ToolAuditProperties properties;

    FrameworkToolAuditMiddleware(ToolInvocationLedger ledger,
                                 ToolAuditSummarizer summarizer,
                                 ToolAuditProperties properties) {
        this.ledger = ledger;
        this.summarizer = summarizer;
        this.properties = properties;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent,
                                     RuntimeContext context,
                                     ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (!properties.isEnabled() || input == null
                || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        Map<String, Pending> pending = new ConcurrentHashMap<>();
        Map<String, StringBuilder> buffers = new ConcurrentHashMap<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (call == null || call.getName() == null || call.getId() == null
                    || call.getId().isBlank() || !isAudited(call.getName())) {
                continue;
            }
            // 兜底：同名调用已在管线审计作用域内时跳过，避免双计。
            if (ToolInvocationScopeHolder.current() != null) {
                continue;
            }
            AgentToolInvocation record = safeStart(context, call);
            if (record == null) {
                continue;
            }
            pending.put(call.getId(), new Pending(record, call));
            buffers.put(call.getId(), new StringBuilder());
        }
        if (pending.isEmpty()) {
            return next.apply(input);
        }
        return next.apply(input)
                .doOnNext(event -> observe(event, pending, buffers))
                .doOnComplete(() -> finishRemaining(pending, buffers,
                        "FAILED", "tool stream completed without a result event"))
                .doOnError(error -> finishRemaining(pending, buffers,
                        "FAILED", error == null ? "tool stream failed" : error.getMessage()));
    }

    private void observe(AgentEvent event,
                         Map<String, Pending> pending,
                         Map<String, StringBuilder> buffers) {
        try {
            if (event instanceof ToolResultTextDeltaEvent delta) {
                append(buffers, delta.getToolCallId(), delta.getDelta());
            } else if (event instanceof ToolResultDataDeltaEvent data) {
                append(buffers, data.getToolCallId(), String.valueOf(data.getData()));
            } else if (event instanceof ToolResultEndEvent end) {
                Pending current = pending.remove(end.getToolCallId());
                if (current != null && isTerminal(end.getState())) {
                    safeFinish(current, buffers, statusOf(end.getState()), "");
                }
            }
        } catch (Exception error) {
            log.debug("[tool-audit] observe failed: {}", error.getMessage());
        }
    }

    private AgentToolInvocation safeStart(RuntimeContext context, ToolUseBlock call) {
        try {
            AgentScopeRunContext run =
                    context == null ? null : context.get(AgentScopeRunContext.class);
            AgentConversation conversation = run == null ? null : run.conversation();
            ToolInvocationContext invocation = ToolInvocationContext.builder()
                    .invocationKey(call.getId() + ":" + call.getName())
                    .conversationId(first(context == null ? null : context.getSessionId(),
                            conversation == null ? null : conversation.getId()))
                    .userId(first(context == null ? null : context.getUserId(),
                            conversation == null ? null : conversation.getUserId()))
                    .projectId(conversation == null || conversation.getProjectId() == null
                            ? "" : conversation.getProjectId())
                    .toolCallId(call.getId())
                    .toolName(call.getName())
                    .toolKind(toolKind(call.getName()))
                    .source("AGENT_SCOPE")
                    .engineVersion(conversation == null || conversation.getEngineVersion() == null
                            ? "" : conversation.getEngineVersion())
                    .build();
            return ledger.start(invocation, call.getInput());
        } catch (Exception error) {
            log.debug("[tool-audit] start failed tool={} error={}",
                    call.getName(), error.getMessage());
            return null;
        }
    }

    private void safeFinish(Pending pending,
                            Map<String, StringBuilder> buffers,
                            String status,
                            String error) {
        try {
            String output = bufferText(buffers.remove(pending.call().getId()));
            ToolInvocationResult result = ToolInvocationResult.builder()
                    .status(status)
                    .outputHash(output == null || output.isEmpty() ? "" : summarizer.hash(output))
                    .outputSummary(outputSummary(output))
                    .errorCode("FAILED".equals(status) ? "TOOL_FAILED" : "")
                    .errorMessage(error == null ? "" : error)
                    .build();
            ledger.finish(pending.record(), result);
        } catch (Exception e) {
            log.debug("[tool-audit] finish failed tool={} error={}",
                    pending.call().getName(), e.getMessage());
        }
    }

    private void finishRemaining(Map<String, Pending> pending,
                                 Map<String, StringBuilder> buffers,
                                 String status,
                                 String error) {
        for (String key : List.copyOf(pending.keySet())) {
            Pending current = pending.remove(key);
            if (current != null) {
                safeFinish(current, buffers, status, error);
            }
        }
    }

    private static void append(Map<String, StringBuilder> buffers, String toolCallId, String chunk) {
        if (toolCallId == null || chunk == null || chunk.isEmpty()) return;
        StringBuilder buffer = buffers.get(toolCallId);
        if (buffer != null) buffer.append(chunk);
    }

    private static String bufferText(StringBuilder buffer) {
        return buffer == null ? "" : buffer.toString();
    }

    private static String outputSummary(String output) {
        if (output == null || output.isBlank()) return "{}";
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("preview", ToolAuditSummarizer.truncate(output, MAX_OUTPUT_CHARS));
        summary.put("chars", output.length());
        try {
            return JSON.writeValueAsString(summary);
        } catch (Exception error) {
            return "{}";
        }
    }

    private static boolean isTerminal(ToolResultState state) {
        return state == ToolResultState.SUCCESS
                || state == ToolResultState.ERROR
                || state == ToolResultState.DENIED
                || state == ToolResultState.INTERRUPTED;
    }

    private static String statusOf(ToolResultState state) {
        return state == ToolResultState.SUCCESS ? "COMPLETED" : "FAILED";
    }

    private static String toolKind(String toolName) {
        return switch (toolName) {
            case "execute" -> "SHELL";
            case "deliver_artifact" -> "DELIVERY";
            default -> "FILE";
        };
    }

    static boolean isAudited(String toolName) {
        return toolName != null && AUDITED_TOOLS.contains(toolName);
    }

    private static String first(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) return primary;
        return fallback == null ? "" : fallback;
    }

    private record Pending(AgentToolInvocation record, ToolUseBlock call) {
    }
}
