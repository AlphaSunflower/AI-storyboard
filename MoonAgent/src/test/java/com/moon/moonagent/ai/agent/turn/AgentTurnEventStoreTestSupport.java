package com.moon.moonagent.ai.agent.turn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Map;

/**
 * 测试替身专用 JSON 工具：与 {@link AgentTurnEventStore} 内部保持同一份序列化配置
 * （注册 JavaTimeModule + 关闭 WRITE_DATES_AS_TIMESTAMPS，红线 3）。
 *
 * <p>只给单测在内存里模拟 store 的落库/补偿用；生产代码不得引用。</p>
 */
public final class AgentTurnEventStoreTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AgentTurnEventStoreTestSupport() {
    }

    public static String toJson(Object payload) {
        try {
            return JSON.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> payloadMap(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        try {
            return JSON.convertValue(payload, Map.class);
        } catch (Exception e) {
            return Map.of("value", String.valueOf(payload));
        }
    }

    public static Map<String, Object> fromJson(String payload) {
        try {
            return JSON.readValue(payload, new TypeReference<>() { });
        } catch (Exception e) {
            return Map.of("raw", payload == null ? "" : payload);
        }
    }
}
