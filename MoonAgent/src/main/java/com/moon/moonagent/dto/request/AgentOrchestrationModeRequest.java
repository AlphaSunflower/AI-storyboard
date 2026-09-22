package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AgentOrchestrationModeRequest(
        @NotBlank(message = "编排模式不能为空") String mode
) {
}
