package com.moon.moonagent.ai.agent.agentscope;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class AgentScopeStructuredTools {

    public static final String BUILD_MEDIA_SPEC = "build_media_spec";
    public static final String ASSESS_GENERATION_RISK = "assess_generation_risk";

    private final StructuredDecisionService decisions;

    public List<AgentScopeTool> tools() {
        return List.of(buildMediaSpec(), assessRisk());
    }

    private AgentScopeTool buildMediaSpec() {
        return new AgentScopeTool(
                BUILD_MEDIA_SPEC,
                "Build one structured media generation specification. Use only when the request "
                        + "needs prompt/parameter/reference decisions; simple explicit requests can "
                        + "call the media tool directly.",
                AgentScopeTool.schema(Map.of(
                        "request", Map.of("type", "string"),
                        "media_type", Map.of("type", "string"),
                        "asset_context", Map.of("type", "string")), List.of("request", "media_type")),
                true, true, false,
                (input, param) -> decisions.buildMediaSpec(
                        string(input.get("request")),
                        string(input.get("media_type")),
                        string(input.get("asset_context"))));
    }

    private AgentScopeTool assessRisk() {
        return new AgentScopeTool(
                ASSESS_GENERATION_RISK,
                "Assess whether a media request should execute directly, ask one focused question, "
                        + "or be rejected. Explicit normal requests should return DIRECT.",
                AgentScopeTool.schema(Map.of(
                        "request", Map.of("type", "string"),
                        "media_type", Map.of("type", "string"),
                        "count", Map.of("type", "integer"),
                        "reference_count", Map.of("type", "integer")),
                        List.of("request", "media_type")),
                true, true, false,
                (input, param) -> decisions.assessRisk(
                        string(input.get("request")),
                        string(input.get("media_type")),
                        integer(input.get("count"), 1),
                        integer(input.get("reference_count"), 0)));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
