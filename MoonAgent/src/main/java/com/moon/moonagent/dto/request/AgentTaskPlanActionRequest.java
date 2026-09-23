package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AgentTaskPlanActionRequest(@NotBlank String action) {
}
