package com.moon.moonagent.ai.agent.recovery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.agent.AgentOrchestrator;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CheckpointRecoverySource implements AgentRecoverySource {

    private final AgentCheckpointMapper checkpointMapper;
    private final AgentConversationMapper conversationMapper;
    private final CheckpointResumeService checkpointResumeService;
    private final AgentOrchestrator orchestrator;

    @Override
    public String name() {
        return "checkpoint";
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(15);
    }

    @Override
    public int batchSize() {
        return 20;
    }

    @Override
    public List<RecoveryCandidate> findCandidates(OffsetDateTime now, int limit) {
        return checkpointMapper.selectList(new LambdaQueryWrapper<AgentCheckpoint>()
                        .and(query -> query
                                .eq(AgentCheckpoint::getStatus, "claimed")
                                .or()
                                .eq(AgentCheckpoint::getStatus, "pending")
                                .apply("resume_payload IS NOT NULL"))
                        .orderByAsc(AgentCheckpoint::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream()
                .map(checkpoint -> new RecoveryCandidate(
                        "checkpoint", checkpoint.getId(), checkpoint.getConversationId(), Map.of()))
                .toList();
    }

    @Override
    public RecoveryOutcome recover(RecoveryCandidate candidate, int attempt, String leaseOwner) {
        CheckpointClaim claim;
        try {
            claim = checkpointResumeService.loadClaimed(candidate, leaseOwner);
        } catch (CheckpointResumeException e) {
            checkpointResumeService.failClaimed(candidate, leaseOwner, e.getMessage());
            return RecoveryOutcome.terminal(e.getMessage());
        }
        if (claim == null) return RecoveryOutcome.completed();
        AgentCheckpoint checkpoint = claim.checkpoint();
        if (checkpoint.getExpirationTime() == null
                || checkpoint.getExpirationTime().isBefore(OffsetDateTime.now())) {
            checkpointResumeService.expire(checkpoint);
            return RecoveryOutcome.completed();
        }
        AgentConversation conversation = conversationMapper.selectById(checkpoint.getConversationId());
        if (conversation == null) {
            checkpointResumeService.failClaimed(candidate, leaseOwner, "conversation missing");
            return RecoveryOutcome.terminal("conversation missing");
        }
        try {
            orchestrator.resumeClaimed(conversation, claim, new SseEmitter(0L));
            return RecoveryOutcome.completed();
        } catch (CheckpointResumeException e) {
            if (e.code() == 40902 || e.code() == 40903) {
                checkpointResumeService.failClaimed(candidate, leaseOwner, e.getMessage());
                return RecoveryOutcome.terminal(e.getMessage());
            }
            return RecoveryOutcome.retryable(e.getMessage());
        }
    }

    @Override
    public RecoveryOutcome exhausted(RecoveryCandidate candidate, String error,
                                     String leaseOwner) {
        checkpointResumeService.failClaimed(candidate, leaseOwner, error);
        return RecoveryOutcome.terminal(error);
    }
}
