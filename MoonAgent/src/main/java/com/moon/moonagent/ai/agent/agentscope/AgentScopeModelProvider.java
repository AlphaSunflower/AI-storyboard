package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentContextWindowResolver;
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
    private final AgentContextWindowResolver contextWindowResolver;
    private final TurnModelPin turnModelPin;

    @Value("${ai.gateway.base-url:${LLM_GATEWAY_BASE_URL:http://localhost:8083}}")
    private String gatewayBaseUrl;

    @Value("${ai.gateway.api-key:${LLM_GATEWAY_API_KEY:}}")
    private String gatewayApiKey;

    /** 动态默认模型包装器（单例：agent 只持有它，模型名在每次调用时按 pin 解析） */
    private volatile Model dynamicPrimary;

    /**
     * 主模型：
     * <ul>
     *   <li>显式配置了 {@code agentscope.model} → 固定用配置值（部署钉死，不受网关默认影响）；</li>
     *   <li>否则 {@code dynamic-default-model} 开启 → 返回动态包装器，切默认模型无需重启（闲置时生效）；</li>
     *   <li>否则回到旧行为：启动时解析一次网关默认模型并固化。</li>
     * </ul>
     */
    public Model primary() {
        String configured = properties.getModel();
        if (configured != null && !configured.isBlank()) {
            return build(configured.trim());
        }
        if (!properties.isDynamicDefaultModel()) {
            return build(modelRouter.resolve("creative.plan"));
        }
        return dynamicPrimary();
    }

    private Model dynamicPrimary() {
        Model current = dynamicPrimary;
        if (current != null) return current;
        synchronized (this) {
            if (dynamicPrimary == null) {
                dynamicPrimary = new GatewayDefaultModel(turnModelPin, this::build);
            }
            return dynamicPrimary;
        }
    }

    public Model fallback() {
        String fallbackName = properties.getFallbackModel();
        if (fallbackName == null || fallbackName.isBlank()
                || fallbackName.equalsIgnoreCase(primary().getModelName())) {
            return null;
        }
        return build(fallbackName.trim());
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
                .contextWindowSize(Math.max(1, contextWindowResolver.resolve(modelName)))
                .nativeStructuredOutputWithTools(false)
                .build();
    }
}
