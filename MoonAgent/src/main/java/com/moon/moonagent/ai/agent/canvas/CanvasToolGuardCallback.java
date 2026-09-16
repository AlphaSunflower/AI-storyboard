package com.moon.moonagent.ai.agent.canvas;

import com.moon.moonagent.ai.agent.AgentInterruptException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Prevents a Canvas MCP tool loop from running forever when the browser stops polling
 * or the user presses "stop generation".
 */
public final class CanvasToolGuardCallback implements ToolCallback {

    private static final Duration DEFAULT_PER_CALL_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofHours(2);
    private static final int DEFAULT_MAX_CALLS = 200;
    private static final Duration INTERRUPT_CHECK_INTERVAL = Duration.ofMillis(100);
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ToolCallback delegate;
    private final BooleanSupplier interrupted;
    private final GuardBudget budget;

    public CanvasToolGuardCallback(ToolCallback delegate, BooleanSupplier interrupted) {
        this(delegate, interrupted, newBudget());
    }

    public CanvasToolGuardCallback(ToolCallback delegate, BooleanSupplier interrupted, GuardBudget budget) {
        this.delegate = delegate;
        this.interrupted = interrupted;
        this.budget = budget;
    }

    CanvasToolGuardCallback(ToolCallback delegate,
                            BooleanSupplier interrupted,
                            Duration perCallTimeout,
                            Duration totalTimeout,
                            int maxCalls) {
        this(delegate, interrupted, new GuardBudget(perCallTimeout, totalTimeout, maxCalls));
    }

    public static GuardBudget newBudget() {
        return new GuardBudget(DEFAULT_PER_CALL_TIMEOUT, DEFAULT_TOTAL_TIMEOUT, DEFAULT_MAX_CALLS);
    }

    static GuardBudget newBudget(Duration perCallTimeout, Duration totalTimeout, int maxCalls) {
        return new GuardBudget(perCallTimeout, totalTimeout, maxCalls);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return guardedCall(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return guardedCall(() -> delegate.call(toolInput, toolContext));
    }

    private String guardedCall(Supplier<String> action) {
        checkInterrupted();
        if (budget.callCount.incrementAndGet() > budget.maxCalls) {
            throw new CanvasToolGuardException("画布工具调用次数已达上限，本轮执行已终止");
        }

        long now = System.nanoTime();
        if (now - budget.startedAt >= budget.totalTimeoutNanos) {
            throw new CanvasToolGuardException("画布工具执行总时长已达上限，本轮执行已终止");
        }

        long callDeadline = now + budget.perCallTimeoutNanos;
        Future<String> future = EXECUTOR.submit(action::get);
        while (true) {
            checkInterrupted();
            long remaining = callDeadline - System.nanoTime();
            if (remaining <= 0) {
                future.cancel(true);
                throw new CanvasToolGuardException("画布工具响应超时，本轮执行已终止");
            }
            try {
                return future.get(Math.min(remaining, INTERRUPT_CHECK_INTERVAL.toNanos()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException ignored) {
                // Keep polling so a user interrupt is observed while the MCP call is blocked.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new AgentInterruptException();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof AgentInterruptException interrupt) throw interrupt;
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            }
        }
    }

    private void checkInterrupted() {
        if (interrupted.getAsBoolean()) throw new AgentInterruptException();
    }

    public static final class CanvasToolGuardException extends RuntimeException {
        public CanvasToolGuardException(String message) {
            super(message);
        }
    }

    public static final class GuardBudget {
        private final long perCallTimeoutNanos;
        private final long totalTimeoutNanos;
        private final int maxCalls;
        private final long startedAt = System.nanoTime();
        private final AtomicInteger callCount = new AtomicInteger();

        private GuardBudget(Duration perCallTimeout, Duration totalTimeout, int maxCalls) {
            this.perCallTimeoutNanos = perCallTimeout.toNanos();
            this.totalTimeoutNanos = totalTimeout.toNanos();
            this.maxCalls = maxCalls;
        }
    }
}
