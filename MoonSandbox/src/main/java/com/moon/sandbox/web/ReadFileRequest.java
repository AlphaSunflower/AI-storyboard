package com.moon.sandbox.web;

import jakarta.validation.constraints.NotBlank;

public record ReadFileRequest(
        @NotBlank String path,
        int offset,
        int maxChars
) {
}
