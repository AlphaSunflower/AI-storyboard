package com.moon.moonagent.ai.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentHandoff;
import com.moon.moonagent.entity.AgentLoopRun;
import com.moon.moonagent.entity.AgentLoopStep;
import com.moon.moonagent.mapper.AgentHandoffMapper;
import com.moon.moonagent.mapper.AgentLoopRunMapper;
import com.moon.moonagent.mapper.AgentLoopStepMapper;
import com.moon.moonagent.orchestration.ChainHandoff;
import com.moon.moonagent.orchestration.OrchestrationMode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrchestrationRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationRunRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentLoopRunMapper runMapper;
    private final AgentLoopStepMapper stepMapper;
    private final AgentHandoffMapper handoffMapper;

    public AgentLoopRun start(String conversationId, String turnId, OrchestrationMode mode,
                              String currentChain, Map<String, Object> state) {
        AgentLoopRun run = new AgentLoopRun();
        run.setConversationId(conversationId);
        run.setTurnId(turnId == null ? "" : turnId);
        run.setTraceId(UUID.randomUUID().toString());
        run.setEngineVersion(mode.engineVersion());
        run.setCurrentChain(currentChain);
        run.setStatus("running");
        run.setState(json(state));
        runMapper.insert(run);
        return run;
    }

    public void step(AgentLoopRun run, int index, String chain, String phase, String status,
                     String decision, String inputSummary, String outputSummary,
                     String toolCallId, String jobId, String error,
                     OffsetDateTime startedAt, OffsetDateTime completedAt) {
        if (run == null) return;
        AgentLoopStep step = new AgentLoopStep();
        step.setLoopRunId(run.getId());
        step.setStepIndex(index);
        step.setChain(chain);
        step.setPhase(phase);
        step.setStatus(status);
        step.setDecision(decision);
        step.setInputSummary(safe(inputSummary));
        step.setOutputSummary(safe(outputSummary));
        step.setToolCallId(toolCallId);
        step.setJobId(jobId);
        step.setError(error);
        step.setStartedAt(startedAt == null ? OffsetDateTime.now() : startedAt);
        step.setCompletedAt(completedAt == null ? OffsetDateTime.now() : completedAt);
        stepMapper.insert(step);
    }

    public void handoff(AgentLoopRun run, ChainHandoff handoff) {
        if (run == null || handoff == null) return;
        AgentHandoff entity = new AgentHandoff();
        entity.setLoopRunId(run.getId());
        entity.setFromChain(handoff.from().name());
        entity.setToChain(handoff.to().name());
        entity.setReason(handoff.reason());
        entity.setState(json(handoff.state()));
        handoffMapper.insert(entity);
    }

    public void finish(AgentLoopRun run, String status, String decision,
                       String currentChain, Map<String, Object> state,
                       String checkpointId, String error) {
        if (run == null) return;
        run.setStatus(status);
        run.setDecision(decision);
        run.setCurrentChain(currentChain);
        run.setState(json(state));
        run.setCheckpointId(checkpointId);
        run.setError(error);
        run.setUpdatedAt(OffsetDateTime.now());
        runMapper.updateById(run);
    }

    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            log.warn("Loop state serialization failed: {}", e.getMessage());
            return "{}";
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
