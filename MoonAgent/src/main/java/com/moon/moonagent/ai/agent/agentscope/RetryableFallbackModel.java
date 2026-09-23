package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Switches models only when the primary stream fails before emitting output and the failure is
 * retryable. Partial streams are never replayed, because doing so would duplicate user-visible
 * text.
 */
final class RetryableFallbackModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(RetryableFallbackModel.class);

    private final Model primary;
    private final Model fallback;
    private final AtomicReference<Model> active = new AtomicReference<>();

    RetryableFallbackModel(Model primary, Model fallback) {
        this.primary = primary;
        this.fallback = fallback;
        this.active.set(primary);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        if (fallback == null) return primary.stream(messages, tools, options);
        return primary.stream(messages, tools, options)
                .switchOnFirst((signal, flux) -> {
                    if (!signal.isOnError()) return flux;
                    Throwable error = signal.getThrowable();
                    if (!isRetryable(error)) return Flux.error(error);
                    active.set(fallback);
                    log.warn("Primary model {} exhausted retries; switching to fallback {}",
                            primary.getModelName(), fallback.getModelName(), error);
                    return fallback.stream(messages, tools, options);
                });
    }

    private static boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (ExecutionConfig.RETRYABLE_ERRORS.test(current)) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    @Override
    public String getModelName() {
        return active.get().getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return active.get().supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return active.get().supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return active.get().getContextWindowSize();
    }
}
