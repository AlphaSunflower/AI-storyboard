package com.moon.moonagent.ai.agent.agentscope.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.agentscope.AgentScopeTurnState;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentWorkflowRun;
import com.moon.moonagent.entity.AgentWorkflowStep;
import com.moon.moonagent.mapper.AgentWorkflowRunMapper;
import com.moon.moonagent.mapper.AgentWorkflowStepMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentWorkflowService {

    private static final List<String> ACTIVE_STATUSES = List.of("pending", "running");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentWorkflowRunMapper runMapper;
    private final AgentWorkflowStepMapper stepMapper;
    private final AgentWorkflowIntentResolver resolver;

    @Transactional
    public AgentWorkflowModels.Plan resolve(AgentConversation conversation, String prompt) {
        AgentWorkflowModels.Resolution resolution = resolver.resolve(prompt);
        if (resolution.mode() == AgentWorkflowModels.Mode.AUTO_SKILL) {
            return new AgentWorkflowModels.Plan("", resolution.mode(), resolution.constraint(), List.of());
        }
        runMapper.update(null, new UpdateWrapper<AgentWorkflowRun>()
                .eq("conversation_id", conversation.getId())
                .in("status", ACTIVE_STATUSES)
                .set("status", "superseded")
                .set("updated_at", OffsetDateTime.now())
                .setSql("version = version + 1"));

        AgentWorkflowRun run = new AgentWorkflowRun();
        run.setConversationId(conversation.getId());
        run.setUserId(conversation.getUserId());
        run.setProjectId(conversation.getProjectId());
        run.setMode(resolution.mode().name());
        run.setStatus("pending");
        run.setOriginalPrompt(prompt == null ? "" : prompt);
        run.setConstraintJson(writeJson(resolution.constraint()));
        run.setVersion(1L);
        runMapper.insert(run);

        List<AgentWorkflowModels.StepView> steps = new ArrayList<>();
        for (int index = 0; index < resolution.steps().size(); index++) {
            AgentWorkflowModels.StepSpec spec = resolution.steps().get(index);
            AgentWorkflowStep entity = new AgentWorkflowStep();
            entity.setWorkflowRunId(run.getId());
            entity.setStepIndex(index);
            entity.setStepKey(spec.key());
            entity.setTitle(spec.title());
            entity.setToolName(spec.toolName());
            entity.setStatus(AgentWorkflowModels.StepStatus.PENDING.name().toLowerCase());
            entity.setDependsOnJson(writeJson(spec.dependsOn()));
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("repeatCount", spec.repeatCount());
            entity.setInputJson(writeJson(input));
            entity.setOutputJson("{}");
            entity.setMarker(spec.marker());
            entity.setAttempts(0);
            stepMapper.insert(entity);
            steps.add(stepView(entity));
        }
        run.setStatus("running");
        run.setUpdatedAt(OffsetDateTime.now());
        runMapper.updateById(run);
        return new AgentWorkflowModels.Plan(run.getId(), resolution.mode(), resolution.constraint(), steps);
    }

    public AgentWorkflowModels.ActiveWorkflow active(String conversationId) {
        AgentWorkflowRun run = activeRun(conversationId);
        if (run == null) return null;
        List<AgentWorkflowStep> entities = steps(run.getId());
        return new AgentWorkflowModels.ActiveWorkflow(
                new AgentWorkflowModels.Plan(
                        run.getId(),
                        mode(run.getMode()),
                        read(run.getConstraintJson(), AgentWorkflowModels.Constraint.class,
                                new AgentWorkflowModels.Constraint(false, false, false, List.of())),
                        entities.stream().map(this::stepView).toList()),
                Map.of("status", run.getStatus()),
                run.getUpdatedAt());
    }

    public String activeContext(String conversationId) {
        AgentWorkflowModels.ActiveWorkflow active = active(conversationId);
        if (active == null) return "";
        StringBuilder out = new StringBuilder();
        out.append("Active user-defined workflow run: ").append(active.plan().runId()).append('\n');
        out.append("Mode: ").append(active.plan().mode()).append('\n');
        out.append("Serial: ").append(active.plan().constraint().serial()).append('\n');
        out.append("Never skip steps. Complete one step before starting the next.\n");
        for (AgentWorkflowModels.StepView step : active.plan().steps()) {
            out.append("- ").append(step.index() + 1).append(". ")
                    .append(step.title()).append(" | tool=").append(step.toolName())
                    .append(" | status=").append(step.status())
                    .append(" | repeat=").append(step.completedCount()).append('/')
                    .append(step.repeatCount());
            if (!step.marker().isBlank()) out.append(" | marker=").append(step.marker());
            out.append('\n');
        }
        return out.toString();
    }

    @Transactional
    public void beforeTool(String conversationId, String toolName, Map<String, Object> input,
                           boolean readOnly) {
        AgentWorkflowRun run = activeRun(conversationId);
        if (run == null || exempt(toolName)) return;
        AgentWorkflowModels.Constraint constraint = read(run.getConstraintJson(),
                AgentWorkflowModels.Constraint.class,
                new AgentWorkflowModels.Constraint(false, false, false, List.of()));
        List<AgentWorkflowStep> steps = steps(run.getId());
        AgentWorkflowStep running = steps.stream()
                .filter(step -> AgentWorkflowModels.StepStatus.RUNNING.name()
                        .equalsIgnoreCase(step.getStatus()))
                .findFirst().orElse(null);
        if (running != null && running.getToolName().equals(toolName)) {
            incrementAttempt(running);
            return;
        }
        if (constraint.serial() && running != null) {
            throw new IllegalStateException("当前工作流必须串行，步骤「"
                    + running.getTitle() + "」尚未完成");
        }
        AgentWorkflowStep candidate = steps.stream()
                .filter(step -> AgentWorkflowModels.StepStatus.PENDING.name()
                        .equalsIgnoreCase(step.getStatus()))
                .filter(step -> dependenciesSucceeded(step, steps))
                .filter(step -> step.getToolName().equals(toolName))
                .findFirst().orElse(null);
        if (candidate == null) {
            if (readOnly) return;
            AgentWorkflowStep next = steps.stream()
                    .filter(step -> AgentWorkflowModels.StepStatus.PENDING.name()
                            .equalsIgnoreCase(step.getStatus()))
                    .filter(step -> dependenciesSucceeded(step, steps))
                    .findFirst().orElse(null);
            throw new IllegalStateException(next == null
                    ? "当前工作流没有可执行步骤"
                    : "下一步必须执行「" + next.getTitle() + "」，工具为 " + next.getToolName());
        }
        candidate.setStatus(AgentWorkflowModels.StepStatus.RUNNING.name().toLowerCase());
        candidate.setStartedAt(OffsetDateTime.now());
        candidate.setAttempts((candidate.getAttempts() == null ? 0 : candidate.getAttempts()) + 1);
        candidate.setInputJson(mergeInput(candidate.getInputJson(), input));
        stepMapper.updateById(candidate);
    }

    @Transactional
    public void afterTool(String conversationId, String toolName, Map<String, Object> output,
                          Throwable error, AgentScopeTurnState turnState) {
        AgentWorkflowRun run = activeRun(conversationId);
        if (run == null || exempt(toolName)) return;
        AgentWorkflowStep running = steps(run.getId()).stream()
                .filter(step -> AgentWorkflowModels.StepStatus.RUNNING.name()
                        .equalsIgnoreCase(step.getStatus()))
                .filter(step -> step.getToolName().equals(toolName))
                .findFirst().orElse(null);
        if (running == null) return;
        if (error != null) {
            running.setStatus(AgentWorkflowModels.StepStatus.FAILED.name().toLowerCase());
            running.setError(rootMessage(error));
            running.setCompletedAt(OffsetDateTime.now());
            stepMapper.updateById(running);
            runMapper.update(null, new UpdateWrapper<AgentWorkflowRun>()
                    .eq("id", run.getId())
                    .set("status", "failed")
                    .set("updated_at", OffsetDateTime.now())
                    .set("completed_at", OffsetDateTime.now()));
            return;
        }
        Map<String, Object> input = readMap(running.getInputJson());
        int repeatCount = number(input.get("repeatCount"), 1);
        running.setOutputJson(writeJson(output == null ? Map.of() : output));
        if ((running.getAttempts() == null ? 0 : running.getAttempts()) < repeatCount) {
            stepMapper.updateById(running);
            return;
        }
        running.setStatus(AgentWorkflowModels.StepStatus.SUCCEEDED.name().toLowerCase());
        running.setCompletedAt(OffsetDateTime.now());
        running.setError(null);
        stepMapper.updateById(running);
        if (turnState != null && running.getMarker() != null && !running.getMarker().isBlank()) {
            turnState.addText(running.getMarker());
        }
        if (steps(run.getId()).stream().allMatch(step ->
                AgentWorkflowModels.StepStatus.SUCCEEDED.name().equalsIgnoreCase(step.getStatus()))) {
            runMapper.update(null, new UpdateWrapper<AgentWorkflowRun>()
                    .eq("id", run.getId())
                    .set("status", "completed")
                    .set("updated_at", OffsetDateTime.now())
                    .set("completed_at", OffsetDateTime.now()));
        }
    }

    private AgentWorkflowRun activeRun(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        return runMapper.selectOne(new LambdaQueryWrapper<AgentWorkflowRun>()
                .eq(AgentWorkflowRun::getConversationId, conversationId)
                .in(AgentWorkflowRun::getStatus, ACTIVE_STATUSES)
                .orderByDesc(AgentWorkflowRun::getUpdatedAt)
                .last("LIMIT 1"));
    }

    private List<AgentWorkflowStep> steps(String runId) {
        return stepMapper.selectList(new LambdaQueryWrapper<AgentWorkflowStep>()
                .eq(AgentWorkflowStep::getWorkflowRunId, runId)
                .orderByAsc(AgentWorkflowStep::getStepIndex));
    }

    private boolean dependenciesSucceeded(AgentWorkflowStep step, List<AgentWorkflowStep> steps) {
        List<String> dependencies = readList(step.getDependsOnJson());
        if (dependencies.isEmpty()) return true;
        return dependencies.stream().allMatch(key -> steps.stream()
                .filter(candidate -> key.equals(candidate.getStepKey()))
                .anyMatch(candidate -> AgentWorkflowModels.StepStatus.SUCCEEDED.name()
                        .equalsIgnoreCase(candidate.getStatus())));
    }

    private AgentWorkflowModels.StepView stepView(AgentWorkflowStep step) {
        Map<String, Object> input = readMap(step.getInputJson());
        return new AgentWorkflowModels.StepView(
                step.getId(),
                step.getStepIndex() == null ? 0 : step.getStepIndex(),
                step.getStepKey(),
                step.getTitle(),
                step.getToolName(),
                stepStatus(step.getStatus()),
                readList(step.getDependsOnJson()),
                number(input.get("repeatCount"), 1),
                Math.min(step.getAttempts() == null ? 0 : step.getAttempts(),
                        number(input.get("repeatCount"), 1)),
                step.getMarker(),
                step.getError()
        );
    }

    private static AgentWorkflowModels.Mode mode(String value) {
        try {
            return AgentWorkflowModels.Mode.valueOf(value);
        } catch (Exception ignored) {
            return AgentWorkflowModels.Mode.AUTO_SKILL;
        }
    }

    private static AgentWorkflowModels.StepStatus stepStatus(String value) {
        try {
            return AgentWorkflowModels.StepStatus.valueOf(value.toUpperCase());
        } catch (Exception ignored) {
            return AgentWorkflowModels.StepStatus.PENDING;
        }
    }

    private void incrementAttempt(AgentWorkflowStep step) {
        step.setAttempts((step.getAttempts() == null ? 0 : step.getAttempts()) + 1);
        step.setUpdatedAt(OffsetDateTime.now());
        stepMapper.updateById(step);
    }

    private static boolean exempt(String toolName) {
        return toolName == null || toolName.isBlank()
                || toolName.equals("resolve_agent_workflow")
                || toolName.startsWith("list_")
                || toolName.startsWith("resolve_")
                || toolName.startsWith("get_");
    }

    private static String mergeInput(String existing, Map<String, Object> input) {
        Map<String, Object> merged = new LinkedHashMap<>(readMap(existing));
        if (input != null) merged.putAll(input);
        return writeJson(merged);
    }

    private static Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return JSON.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<String> readList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return JSON.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private <T> T read(String value, Class<T> type, T fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return JSON.readValue(value, type);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
