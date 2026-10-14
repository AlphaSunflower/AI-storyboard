package com.moon.sandbox.web;

import com.moon.sandbox.session.SandboxPathException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxExceptionHandlerTest {

    private final SandboxExceptionHandler handler = new SandboxExceptionHandler();

    @Test
    void mapsCapacityExhaustionToTooManyRequests() {
        var response = handler.handleSandboxError(
                new SandboxPathException("sandbox execution capacity is full"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void mapsExecutionFailureToBadRequestAndKeepsCauseInBody() {
        var response = handler.handleSandboxError(new SandboxPathException(
                "unable to execute shell command: Cannot run program \"sh\""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("error", "unable to execute shell command: Cannot run program \"sh\"");
    }

    @Test
    void mapsPlainPathErrorToBadRequest() {
        var response = handler.handleSandboxError(
                new SandboxPathException("path escapes sandbox root"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "path escapes sandbox root"));
    }

    @Test
    void treatsAFailureMessageMentioningFullAsBadRequestNotRateLimit() {
        var response = handler.handleSandboxError(new SandboxPathException(
                "unable to execute python script: No space left, disk full"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
