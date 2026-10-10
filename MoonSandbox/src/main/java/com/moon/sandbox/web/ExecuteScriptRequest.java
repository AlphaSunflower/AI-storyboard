package com.moon.sandbox.web;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ExecuteScriptRequest(
        @NotBlank String script,
        List<String> args,
        int timeoutSeconds
) {
}
