package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class StructuredDecisionService {

    private static final Logger log = LoggerFactory.getLogger(StructuredDecisionService.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(45);

    private final AgentScopeProperties properties;
    private final AgentScopeModelProvider modelProvider;
    private final ObjectMapper json = new ObjectMapper();

    private volatile ReActAgent agent;

    @PostConstruct
    void init() {
        if (!properties.isStructuredOutputEnabled()) return;
        agent = ReActAgent.builder()
                .name("moon-structured-decisions")
                .sysPrompt("Return only the requested structured decision. Be concise and precise.")
                .model(modelProvider.withFallback())
                .maxIters(2)
                .maxRetries(properties.getMaxRetries())
                .build();
    }

    public AgentScopeStructuredModels.GenerationSpec buildMediaSpec(
            String request, String mediaType, String assetContext) {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        props.putObject("mediaType").put("type", "string");
        props.putObject("prompt").put("type", "string");
        props.putObject("size").put("type", "string");
        props.putObject("quality").put("type", "string");
        props.putObject("model").put("type", "string");
        props.putObject("duration").put("type", "string");
        props.putObject("aspectRatio").put("type", "string");
        props.putObject("referenceAssetIds").put("type", "array")
                .put("items", textSchema());
        props.putObject("confidence").put("type", "number");
        props.putObject("rationale").put("type", "string");
        required(schema, "mediaType", "prompt", "referenceAssetIds", "confidence", "rationale");
        JsonNode result = structuredCall("""
                Build one executable media-generation specification.
                media type: %s
                user request: %s
                available session assets:
                %s
                """.formatted(mediaType, request, assetContext), schema);
        return convert(result, AgentScopeStructuredModels.GenerationSpec.class,
                new AgentScopeStructuredModels.GenerationSpec(
                        mediaType, request, "", "", "", "", "", List.of(), 0.5, "fallback"));
    }

    public AgentScopeStructuredModels.ReferenceSelection selectReferences(
            String request, List<SessionAssetRef> candidates) {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        props.putObject("assetIds").put("type", "array").put("items", textSchema());
        props.putObject("confidence").put("type", "number");
        props.putObject("rationale").put("type", "string");
        required(schema, "assetIds", "confidence", "rationale");
        String candidateText;
        try {
            candidateText = json.writeValueAsString(candidates);
        } catch (Exception e) {
            candidateText = String.valueOf(candidates);
        }
        JsonNode result = structuredCall("""
                Select only the session assets needed for this media request.
                request: %s
                candidates: %s
                """.formatted(request, candidateText), schema);
        return convert(result, AgentScopeStructuredModels.ReferenceSelection.class,
                new AgentScopeStructuredModels.ReferenceSelection(List.of(), 0.0, "fallback"));
    }

    public AgentScopeStructuredModels.RiskDecision assessRisk(
            String request, String mediaType, int count, int referenceCount) {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        props.putObject("decision").put("type", "string")
                .putArray("enum").add("DIRECT").add("ASK").add("REJECT");
        props.putObject("reason").put("type", "string");
        props.putObject("question").put("type", "string");
        required(schema, "decision", "reason", "question");
        JsonNode result = structuredCall("""
                Assess whether this media generation should run directly.
                Explicit requests are authorized. Ask only for genuine ambiguity or unusually
                expensive batch work. Reject only unsafe or destructive operations.
                mediaType=%s, count=%d, references=%d, request=%s
                """.formatted(mediaType, count, referenceCount, request), schema);
        return convert(result, AgentScopeStructuredModels.RiskDecision.class,
                new AgentScopeStructuredModels.RiskDecision("DIRECT", "fallback", ""));
    }

    public AgentScopeStructuredModels.SubagentResult parseSubagentResult(String response) {
        if (response == null || response.isBlank()) {
            return new AgentScopeStructuredModels.SubagentResult("", List.of(), List.of());
        }
        try {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode root = json.readTree(response.substring(start, end + 1));
                return convert(root, AgentScopeStructuredModels.SubagentResult.class,
                        new AgentScopeStructuredModels.SubagentResult(response, List.of(), List.of()));
            }
        } catch (Exception e) {
            log.debug("Subagent result was not JSON: {}", e.getMessage());
        }
        return new AgentScopeStructuredModels.SubagentResult(response, List.of(), List.of());
    }

    private JsonNode structuredCall(String prompt, JsonNode schema) {
        if (!properties.isStructuredOutputEnabled() || agent == null) {
            return json.createObjectNode();
        }
        try {
            RuntimeContext context = RuntimeContext.builder()
                    .sessionId("structured-" + UUID.randomUUID())
                    .build();
            var result = agent.call(List.of(new UserMessage(prompt)), schema, context)
                    .block(CALL_TIMEOUT);
            if (result == null || !result.hasStructuredData()) return json.createObjectNode();
            return json.valueToTree(result.getStructuredData(true));
        } catch (Exception e) {
            log.warn("Structured decision failed: {}", e.getMessage());
            return json.createObjectNode();
        }
    }

    private <T> T convert(JsonNode node, Class<T> type, T fallback) {
        if (node == null || node.isEmpty()) return fallback;
        try {
            return json.treeToValue(node, type);
        } catch (Exception e) {
            log.warn("Structured decision conversion failed: {}", e.getMessage());
            return fallback;
        }
    }

    private static ObjectNode objectSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode textSchema() {
        return new ObjectMapper().createObjectNode().put("type", "string");
    }

    private static void required(ObjectNode schema, String... fields) {
        ArrayNode array = schema.putArray("required");
        for (String field : fields) array.add(field);
    }

    @PreDestroy
    void close() {
        if (agent != null) {
            try {
                agent.close();
            } catch (Exception ignored) {
                // Application shutdown.
            }
        }
    }
}
