package com.moon.sandbox.web;

import jakarta.validation.constraints.NotBlank;

public record ShellCommandRequest(
        @NotBlank String command,
        int timeoutSeconds
) {
}
