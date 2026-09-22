package com.moon.moonagent.ai.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.orchestration.CapabilityDescriptor;
import com.moon.moonagent.orchestration.TaskPlanDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LlmTaskPlanSynthesizer implements TaskPlanSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(LlmTaskPlanSynthesizer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatClient chatClient;
    private final BeanOutputConverter<TaskPlanDraft> converter = new BeanOutputConverter<>(TaskPlanDraft.class);

    public LlmTaskPlanSynthesizer(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public TaskPlanDraft synthesize(TaskPlanningRequest request,
                                     Map<String, CapabilityDescriptor> capabilities,
                                     List<String> validationErrors) {
        try {
            String capabilityJson = JSON.writeValueAsString(capabilities.values());
            String errorText = validationErrors == null || validationErrors.isEmpty()
                    ? "none"
                    : String.join("; ", validationErrors);
            String raw = chatClient.prompt()
                    .system(systemPrompt())
                    .user("""
                            Goal: %s
                            Preferred capability: %s
                            Available capabilities JSON: %s
                            Previous validation errors: %s
                            """.formatted(request.goal(), request.preferredCapability(), capabilityJson, errorText))
                    .call()
                    .content();
            TaskPlanDraft draft = converter.convert(raw);
            if (draft != null && !draft.nodes().isEmpty()) {
                return draft;
            }
            throw new IllegalStateException("LLM task plan synthesis returned no nodes");
        } catch (Exception e) {
            log.warn("LLM task plan synthesis failed: {}", e.getMessage());
            throw new IllegalStateException("LLM task plan synthesis failed: " + e.getMessage(), e);
        }
    }

    private static String systemPrompt() {
        return """
                You plan a short executable DAG for MoonAgent.
                Use only capability keys listed in the available capabilities JSON.
                Return JSON matching TaskPlanDraft: {goal, nodes, edges}.
                Each node must contain: nodeId, capabilityKey, title, parameters, dependencies,
                state, attemptCount, hitlPolicy, evaluationMode, qualityCriteria, outputSummary,
                asyncTaskId, error.
                Use PENDING state, attemptCount 0 and empty strings for runtime fields.
                Dependencies must refer to nodeId values in the same plan. Never create cycles.
                Prefer the smallest plan that satisfies the user's goal.
                If the goal needs media followed by another step, put the media node before the dependent node.
                Do not invent capabilities or execute tools yourself.
                """;
    }

}
