package com.moon.moonagent.ai.agent.canvas;

import com.moon.moonagent.ai.agent.AgentInterruptException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CanvasToolGuardCallbackTest {

    @Test
    void returnsNormalToolResult() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.call(anyString())).thenReturn("{\"ok\":true}");
        CanvasToolGuardCallback callback = new CanvasToolGuardCallback(
                delegate, () -> false, Duration.ofSeconds(1), Duration.ofSeconds(1), 3);

        assertThat(callback.call("{}")).isEqualTo("{\"ok\":true}");
    }

    @Test
    void abortsBlockedToolWhenUserStopsGeneration() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.call(anyString())).thenAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return "late";
        });
        CanvasToolGuardCallback callback = new CanvasToolGuardCallback(
                delegate, interrupted::get, Duration.ofSeconds(5), Duration.ofSeconds(5), 3);

        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> callback.call("{}"));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        interrupted.set(true);

        assertThatThrownBy(result::join)
                .hasCauseInstanceOf(AgentInterruptException.class);
        release.countDown();
    }

    @Test
    void abortsToolThatDoesNotReturnBeforeTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.call(anyString())).thenAnswer(invocation -> {
            release.await(2, TimeUnit.SECONDS);
            return "late";
        });
        CanvasToolGuardCallback callback = new CanvasToolGuardCallback(
                delegate, () -> false, Duration.ofMillis(50), Duration.ofSeconds(1), 3);

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(CanvasToolGuardCallback.CanvasToolGuardException.class)
                .hasMessageContaining("响应超时");
        release.countDown();
    }

    @Test
    void rejectsToolCallsAfterBudgetIsExhausted() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.call(anyString())).thenReturn("ok");
        CanvasToolGuardCallback callback = new CanvasToolGuardCallback(
                delegate, () -> false, Duration.ofSeconds(1), Duration.ofSeconds(1), 1);

        assertThat(callback.call("{}")).isEqualTo("ok");
        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(CanvasToolGuardCallback.CanvasToolGuardException.class)
                .hasMessageContaining("次数已达上限");
    }

    @Test
    void sharesCallBudgetAcrossDifferentCanvasTools() {
        ToolCallback firstDelegate = mock(ToolCallback.class);
        ToolCallback secondDelegate = mock(ToolCallback.class);
        when(firstDelegate.call(anyString())).thenReturn("first");
        when(secondDelegate.call(anyString())).thenReturn("second");
        CanvasToolGuardCallback.GuardBudget budget = CanvasToolGuardCallback.newBudget(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
        CanvasToolGuardCallback first = new CanvasToolGuardCallback(firstDelegate, () -> false, budget);
        CanvasToolGuardCallback second = new CanvasToolGuardCallback(secondDelegate, () -> false, budget);

        assertThat(first.call("{}")).isEqualTo("first");
        assertThatThrownBy(() -> second.call("{}"))
                .isInstanceOf(CanvasToolGuardCallback.CanvasToolGuardException.class)
                .hasMessageContaining("次数已达上限");
    }
}
