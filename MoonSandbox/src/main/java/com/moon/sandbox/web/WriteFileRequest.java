package com.moon.sandbox.web;

import com.moon.sandbox.session.WriteMode;
import jakarta.validation.constraints.NotBlank;

public record WriteFileRequest(
        @NotBlank String path,
        String content,
        WriteMode mode
) {
}
