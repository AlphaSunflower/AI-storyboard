package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.creative.CreativeModelRouter;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class AgentScopeModelProvider {

    private final AgentScopeProperties properties;
    private final CreativeModelRouter modelRouter;

    @Value("${ai.gateway.base-url:${LLM_GATEWAY_BASE_URL:http://localhost:8083}}")
    private String gatewayBaseUrl;

    @Value("${ai.gateway.api-key:${LLM_GATEWAY_API_KEY:}}")
    private String gatewayApiKey;

    public Model primary() {
        String modelName = properties.getModel() == null || properties.getModel().isBlank()
                ? modelRouter.resolve("creative.plan") : properties.getModel().trim();
        return build(modelName);
    }

    public Model withFallback() {
        Model primary = primary();
        String fallbackName = properties.getFallbackModel();
        if (fallbackName == null || fallbackName.isBlank()
                || fallbackName.equalsIgnoreCase(primary.getModelName())) {
            return primary;
        }
        return new RetryableFallbackModel(primary, build(fallbackName.trim()));
    }

    public Model resolveNamed(String modelName) {
        if (modelName == null || modelName.isBlank()) return primary();
        return build(modelName.trim());
    }

    private OpenAIChatModel build(String modelName) {
        return OpenAIChatModel.builder()
                .apiKey(gatewayApiKey)
                .baseUrl(gatewayBaseUrl)
                .modelName(modelName)
                .stream(true)
                .nativeStructuredOutputWithTools(false)
                .build();
    }
}
