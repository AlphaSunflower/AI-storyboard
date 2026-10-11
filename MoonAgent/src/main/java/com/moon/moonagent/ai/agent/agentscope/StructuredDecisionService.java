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
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class StructuredDecisionService {

    private static final Logger log = LoggerFactory.getLogger(StructuredDecisionService.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(45);
    private static final ObjectMapper SCHEMA_JSON = new ObjectMapper();

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
                .model(modelProvider.primary())
                .fallbackModel(modelProvider.fallback())
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

    public AgentScopeStructuredModels.MediaActionReview reviewMediaAction(
            String toolName, JsonNode input, String turnContext,
            List<SessionAssetRef> referenceCandidates) {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        props.putObject("decision").put("type", "string")
                .putArray("enum").add("EXECUTE").add("REPAIR").add("ASK").add("REJECT");
        props.putObject("repairedInput").put("type", "object")
                .put("additionalProperties", true);
        props.putObject("question").put("type", "string");
        props.putObject("confidence").put("type", "number");
        props.putObject("reasons").put("type", "array").put("items", textSchema());
        required(schema, "decision", "repairedInput", "question", "confidence", "reasons");
        String inputJson;
        String candidatesJson;
        try {
            inputJson = json.writeValueAsString(input == null ? json.createObjectNode() : input);
            candidatesJson = json.writeValueAsString(
                    referenceCandidates == null ? List.of() : referenceCandidates);
        } catch (Exception e) {
            inputJson = String.valueOf(input);
            candidatesJson = String.valueOf(referenceCandidates);
        }
        JsonNode result = structuredCall("""
                Review one media-tool invocation before it can cause external side effects.
                Return EXECUTE only when the input is directly safe to run.
                Return REPAIR with a complete corrected tool input when the intent is clear.
                Return ASK only for genuine ambiguity and provide one focused question.
                Return REJECT only for unsafe or explicitly invalid requests.
                Never invent an asset id that is not present in reference candidates.
                tool: %s
                input: %s
                turn context: %s
                reference candidates: %s
                """.formatted(toolName, inputJson, turnContext, candidatesJson),
                schema, properties.getPreflight().getTimeout());
        return convert(result, AgentScopeStructuredModels.MediaActionReview.class,
                new AgentScopeStructuredModels.MediaActionReview(
                        "EXECUTE", Map.of(), "", 0.0, List.of("fallback")));
    }

    public AgentScopeStructuredModels.WorkflowDecision resolveWorkflow(
            String userPrompt, String capabilityCatalog) {
        ObjectNode schema = objectSchema();
        ObjectNode props = schema.putObject("properties");
        props.putObject("mode").put("type", "string")
                .putArray("enum")
                .add("AUTO_SKILL")
                .add("USER_DEFINED_WORKFLOW")
                .add("HYBRID");
        props.putObject("serial").put("type", "boolean");
        props.putObject("noSkip").put("type", "boolean");
        props.putObject("requireMarkers").put("type", "boolean");
        props.putObject("exactCounts").put("type", "array")
                .putObject("items").put("type", "integer");
        ObjectNode steps = props.putObject("steps");
        steps.put("type", "array");
        ObjectNode step = steps.putObject("items");
        step.put("type", "object");
        step.put("additionalProperties", false);
        ObjectNode stepProps = step.putObject("properties");
        stepProps.putObject("key").put("type", "string");
        stepProps.putObject("title").put("type", "string");
        stepProps.putObject("toolName").put("type", "string");
        stepProps.putObject("dependsOn").put("type", "array")
                .putObject("items").put("type", "string");
        stepProps.putObject("repeatCount").put("type", "integer");
        stepProps.putObject("marker").put("type", "string");
        required(step, "key", "title", "toolName", "dependsOn", "repeatCount", "marker");
        props.putObject("confidence").put("type", "number");
        props.putObject("rationale").put("type", "string");
        required(schema, "mode", "serial", "noSkip", "requireMarkers",
                "exactCounts", "steps", "confidence", "rationale");
        JsonNode result = structuredCall("""
                Decide whether the user requested a custom multi-step workflow.
                Use AUTO_SKILL for a normal single goal.
                Use USER_DEFINED_WORKFLOW when the user explicitly defines ordered stages,
                serial execution, no-skips, stage markers, or step dependencies.
                Use HYBRID when only part of the process is defined.
                Compile the user's process into executable steps without inventing extra user stages.
                Choose toolName only from the capability catalog.
                Preserve exact counts, ordering, markers, and dependencies.
                user prompt:
                %s
                capability catalog:
                %s
                """.formatted(userPrompt, capabilityCatalog), schema);
        return convert(result, AgentScopeStructuredModels.WorkflowDecision.class,
                new AgentScopeStructuredModels.WorkflowDecision(
                        "", false, false, false, List.of(), List.of(), 0.0, "fallback"));
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
        return structuredCall(prompt, schema, CALL_TIMEOUT);
    }

    private JsonNode structuredCall(String prompt, JsonNode schema, Duration timeout) {
        if (!properties.isStructuredOutputEnabled() || agent == null) {
            return json.createObjectNode();
        }
        try {
            RuntimeContext context = RuntimeContext.builder()
                    .sessionId("structured-" + UUID.randomUUID())
                    .build();
            var result = agent.call(List.of(new UserMessage(prompt)), schema, context)
                    .block(timeout == null ? CALL_TIMEOUT : timeout);
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
        ObjectNode schema = SCHEMA_JSON.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode textSchema() {
        return SCHEMA_JSON.createObjectNode().put("type", "string");
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
