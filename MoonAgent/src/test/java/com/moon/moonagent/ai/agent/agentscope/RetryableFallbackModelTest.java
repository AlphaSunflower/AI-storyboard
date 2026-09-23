package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryableFallbackModelTest {

    @Test
    void switchesToFallbackForRetryableFailureBeforeOutput() {
        Model primary = model("primary", Flux.error(new IOException("network")));
        Model fallback = model("fallback", Flux.empty());
        RetryableFallbackModel model = new RetryableFallbackModel(primary, fallback);

        assertThat(model.stream(List.of(), List.of(), null).collectList().block()).isEmpty();
        verify(fallback).stream(any(), any(), any());
        assertThat(model.getModelName()).isEqualTo("fallback");
    }

    @Test
    void doesNotFallbackForNonRetryableFailure() {
        Model primary = model("primary", Flux.error(new IllegalArgumentException("bad request")));
        Model fallback = model("fallback", Flux.empty());
        RetryableFallbackModel model = new RetryableFallbackModel(primary, fallback);

        assertThatThrownBy(() -> model.stream(List.of(), List.of(), null).collectList().block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad request");
        verify(fallback, never()).stream(any(), any(), any());
        assertThat(model.getModelName()).isEqualTo("primary");
    }

    private static Model model(String name, Flux<?> flux) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn(name);
        when(model.stream(any(), any(), any())).thenReturn((Flux) flux);
        return model;
    }
}
