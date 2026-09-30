package com.moon.moonagent.ai.agent.creative;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.agentscope.AgentScopeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class CreativeModelRouter {

    private final AgentScopeProperties properties;
    private final GatewayModelService gatewayModelService;

    public String resolve(String role) {
        String normalized = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        AgentScopeProperties.Models models = properties.getModels();
        return switch (normalized) {
            case "plan", "creative.plan" -> first(models.getPlan(),
                    gatewayModelService.getDefaultTextModel());
            case "prompt", "creative.prompt" -> first(models.getPrompt(),
                    gatewayModelService.getDefaultTextModel());
            case "critic", "creative.critic", "vision.qa" -> first(models.getCritic(),
                    gatewayModelService.getDefaultVisionModel());
            case "vision" -> first(models.getVision(),
                    gatewayModelService.getDefaultVisionModel());
            case "image", "image.default" -> models.getImage();
            case "video", "video.default" -> models.getVideo();
            default -> gatewayModelService.getDefaultTextModel();
        };
    }

    public String resolveOrNull(String role) {
        String value = resolve(role);
        return value == null || value.isBlank() ? null : value;
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
