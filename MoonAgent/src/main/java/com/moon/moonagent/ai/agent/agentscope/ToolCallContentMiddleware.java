package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * AgentScope 2.0.4 may leave ToolUseBlock.content null for streamed or
 * provider-specific tool calls. ToolValidator then fails before invoking the
 * tool. Restore the canonical JSON argument string from the parsed input map.
 *
 * <p>每次实际补齐都会打 INFO 日志并累加 Micrometer 计数，用来判断这条兜底是否还有存在必要：
 * 上游不再以 {@code ToolUseBlock.content} 作为 ToolValidator 的唯一输入，且该计数在完整发布
 * 周期内保持为 0，才可以考虑删除本中间件。
 */
@Component
public final class ToolCallContentMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolCallContentMiddleware.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 计数名：实际发生 content 补齐的次数。 */
    static final String REWRITE_METRIC = "agent_tool_call_content_rewrite_total";

    private final MeterRegistry meters;

    public ToolCallContentMiddleware() {
        this(null);
    }

    @Autowired
    public ToolCallContentMiddleware(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        List<ToolUseBlock> normalized = new ArrayList<>(input.toolCalls().size());
        boolean changed = false;
        for (ToolUseBlock call : input.toolCalls()) {
            if (call == null || call.getContent() != null && !call.getContent().isBlank()) {
                normalized.add(call);
                continue;
            }
            normalized.add(new ToolUseBlock(
                    call.getId(),
                    call.getName(),
                    call.getInput(),
                    argumentsJson(call.getInput()),
                    call.getMetadata(),
                    call.getState()));
            changed = true;
            recordRewrite(call);
        }
        return next.apply(changed ? new ActingInput(normalized) : input);
    }

    private void recordRewrite(ToolUseBlock call) {
        try {
            String tool = call.getName() == null ? "unknown" : call.getName();
            int length = argumentsJson(call.getInput()).length();
            log.info("[tool-audit] restored missing tool-call content tool={} callId={} chars={}",
                    tool, call.getId(), length);
            if (meters != null) {
                meters.counter(REWRITE_METRIC, "tool", tool).increment();
            }
        } catch (Exception error) {
            // 可观测性绝不影响到工具执行本身
            log.debug("[tool-audit] content rewrite metric failed: {}", error.getMessage());
        }
    }

    private static String argumentsJson(Map<String, Object> input) {
        try {
            return OBJECT_MAPPER.writeValueAsString(input == null ? Map.of() : input);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
