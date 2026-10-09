package com.moon.moonagent.ai.agent;

import com.moon.moonagent.ai.GatewayModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型上下文窗口解析：模型名 → 窗口 token 数。
 *
 * <p>权威源为网关 {@code model_params.context_window}（经 {@code /v1/models} 透出）；
 * 网关不可达或模型未配置时回退 {@link #STATIC_WINDOW_BY_PREFIX} 内置表，再回退
 * {@link #DEFAULT_WINDOW_TOKENS}。
 */
@Component
@RequiredArgsConstructor
public class AgentContextWindowResolver {

    /** 网关与内置表均无法解析时的回退窗口。 */
    public static final int DEFAULT_WINDOW_TOKENS = 128_000;

    private static final Map<String, Integer> STATIC_WINDOW_BY_PREFIX = new LinkedHashMap<>();

    static {
        STATIC_WINDOW_BY_PREFIX.put("claude-sonnet-4", 200_000);
        STATIC_WINDOW_BY_PREFIX.put("claude-opus-4", 200_000);
        STATIC_WINDOW_BY_PREFIX.put("claude-3-7", 200_000);
        STATIC_WINDOW_BY_PREFIX.put("claude-3-5", 200_000);
        STATIC_WINDOW_BY_PREFIX.put("gemini-2.5-pro", 1_000_000);
        STATIC_WINDOW_BY_PREFIX.put("gemini-2.5-flash", 1_000_000);
        STATIC_WINDOW_BY_PREFIX.put("gemini-3", 1_000_000);
        STATIC_WINDOW_BY_PREFIX.put("gemini-2.0", 1_000_000);
        STATIC_WINDOW_BY_PREFIX.put("gpt-4.1", 1_000_000);
        STATIC_WINDOW_BY_PREFIX.put("gpt-4o", 128_000);
        STATIC_WINDOW_BY_PREFIX.put("o4-mini", 200_000);
        STATIC_WINDOW_BY_PREFIX.put("o3", 200_000);
        // DeepSeek / MiMo 全系按 1M 窗口。
        STATIC_WINDOW_BY_PREFIX.put("deepseek", 1_000_000);
        STATIC_WINDOW_BY_PREFIX.put("mimo", 1_000_000);
        STATIC_WINDOW_BY_PREFIX.put("qwen3-max", 128_000);
        STATIC_WINDOW_BY_PREFIX.put("kimi-k2", 200_000);
    }

    private final GatewayModelService gatewayModelService;

    /** 解析模型上下文窗口：网关配置优先，其次内置表，最后默认值。 */
    public int resolve(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            try {
                Integer fromGateway = gatewayModelService.resolveContextWindow(modelName.trim());
                if (fromGateway != null && fromGateway > 0) return fromGateway;
            } catch (Exception ignored) {
                // 网关异常：静默回退内置表
            }
        }
        return resolveStatic(modelName);
    }

    /** 仅内置表回退（网关缺失或单测使用）。 */
    static int resolveStatic(String modelName) {
        if (modelName == null || modelName.isBlank()) return DEFAULT_WINDOW_TOKENS;
        String key = modelName.trim().toLowerCase();
        int best = -1;
        int bestLength = 0;
        for (Map.Entry<String, Integer> entry : STATIC_WINDOW_BY_PREFIX.entrySet()) {
            if (key.contains(entry.getKey()) && entry.getKey().length() > bestLength) {
                best = entry.getValue();
                bestLength = entry.getKey().length();
            }
        }
        return best > 0 ? best : DEFAULT_WINDOW_TOKENS;
    }
}
