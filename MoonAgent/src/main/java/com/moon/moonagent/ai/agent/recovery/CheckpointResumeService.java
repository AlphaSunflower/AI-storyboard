package com.moon.moonagent.ai.agent.recovery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentCheckpointRuntime;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class CheckpointResumeService {

    public static final int AGENTSCOPE_SCHEMA_VERSION = 3;
    public static final int UNIFIED_SCHEMA_VERSION = 1;

    private final AgentCheckpointMapper checkpointMapper;
    private final AgentRecoveryLeaseService leases;
    private final ObjectMapper objectMapper;

    @Autowired
    public CheckpointResumeService(AgentCheckpointMapper checkpointMapper,
                                   AgentRecoveryLeaseService leases) {
        this(checkpointMapper, leases, new ObjectMapper());
    }

    CheckpointResumeService(AgentCheckpointMapper checkpointMapper,
                            AgentRecoveryLeaseService leases,
                            ObjectMapper objectMapper) {
        this.checkpointMapper = checkpointMapper;
        this.leases = leases;
        this.objectMapper = objectMapper;
    }

    @Transactional(noRollbackFor = CheckpointExpirationException.class)
    public CheckpointClaim claim(String conversationId, String formToken,
                                 ResumeCommand command) {
        AgentCheckpoint checkpoint = find(conversationId, formToken);
        if (checkpoint == null) {
            throw new CheckpointResumeException(40401, "确认信息不存在或已失效，请重新发起");
        }
        if ("expired".equals(checkpoint.getStatus())) {
            throw new CheckpointResumeException(40001, "确认已失效，请重新发起");
        }
        if ("used".equals(checkpoint.getStatus())) {
            throw new CheckpointResumeException(40001, "确认已被使用，请重新发起");
        }
        if ("failed".equals(checkpoint.getStatus())) {
            throw new CheckpointResumeException(40001, "确认处理失败，请重新发起");
        }
        if (checkpoint.getExpirationTime() == null
                || checkpoint.getExpirationTime().isBefore(OffsetDateTime.now())) {
            expire(checkpoint);
            throw new CheckpointExpirationException("确认已失效，请重新发起");
        }
        validateVersion(checkpoint);
        hydrateRuntime(checkpoint);
        String owner = "user-" + UUID.randomUUID();
        RecoveryCandidate candidate = candidate(checkpoint);
        if (leases.tryClaim(candidate.resourceType(), candidate.resourceId(), owner) == 0) {
            throw new CheckpointResumeException(40901, "确认正在处理中，请稍候");
        }
        checkpoint.setStatus("claimed");
        checkpoint.setResumePayload(serialize(command));
        int updated = checkpointMapper.update(null, new UpdateWrapper<AgentCheckpoint>()
                .eq("id", checkpoint.getId())
                .in("status", "pending", "claimed")
                .set("status", "claimed")
                .set("resume_payload", checkpoint.getResumePayload())
                .set("updated_at", OffsetDateTime.now()));
        if (updated == 0) {
            leases.release(candidate, owner);
            throw new CheckpointResumeException(40901, "确认正在处理中，请稍候");
        }
        return new CheckpointClaim(checkpoint, command, owner);
    }

    public CheckpointClaim loadClaimed(RecoveryCandidate candidate, String owner) {
        AgentCheckpoint checkpoint = checkpointMapper.selectById(candidate.resourceId());
        if (checkpoint == null) return null;
        if (!"claimed".equals(checkpoint.getStatus()) && !"pending".equals(checkpoint.getStatus())) {
            return null;
        }
        if (!leases.owns(candidate, owner)) return null;
        validateVersion(checkpoint);
        hydrateRuntime(checkpoint);
        if ("pending".equals(checkpoint.getStatus())
                && (checkpoint.getResumePayload() == null
                || checkpoint.getResumePayload().isBlank())) {
            return null;
        }
        ResumeCommand command = deserialize(checkpoint.getResumePayload());
        return new CheckpointClaim(checkpoint, command, owner);
    }

    public boolean hasActiveClaim(AgentCheckpoint checkpoint) {
        return checkpoint != null
                && leases.hasActiveLease("checkpoint", checkpoint.getId());
    }

    @Transactional
    public boolean complete(CheckpointClaim claim) {
        if (claim == null) return false;
        int updated = checkpointMapper.transitionIfLeased(
                claim.checkpoint().getId(), "used", claim.leaseOwner());
        if (updated == 0) return false;
        leases.release(candidate(claim.checkpoint()), claim.leaseOwner());
        return true;
    }

    @Transactional
    public boolean retry(CheckpointClaim claim, String error) {
        if (claim == null) return false;
        int updated = checkpointMapper.transitionIfLeased(
                claim.checkpoint().getId(), "pending", claim.leaseOwner());
        if (updated == 0) return false;
        RecoveryCandidate candidate = candidate(claim.checkpoint());
        leases.scheduleRetry(candidate, claim.leaseOwner(),
                leases.attempt(candidate, claim.leaseOwner()), error);
        return true;
    }

    @Transactional
    public boolean fail(CheckpointClaim claim, String error) {
        if (claim == null) return false;
        int updated = checkpointMapper.transitionIfLeased(
                claim.checkpoint().getId(), "failed", claim.leaseOwner());
        if (updated == 0) return false;
        leases.release(candidate(claim.checkpoint()), claim.leaseOwner());
        return true;
    }

    @Transactional
    public boolean failClaimed(RecoveryCandidate candidate, String owner, String error) {
        if (candidate == null) return false;
        int updated = checkpointMapper.transitionIfLeased(
                candidate.resourceId(), "failed", owner);
        if (updated == 0) return false;
        leases.release(candidate, owner);
        return true;
    }

    public void expire(AgentCheckpoint checkpoint) {
        if (checkpoint == null) return;
        checkpointMapper.update(null, new UpdateWrapper<AgentCheckpoint>()
                .eq("id", checkpoint.getId())
                .ne("status", "used")
                .set("status", "expired"));
    }

    public int expirePending(String conversationId) {
        return checkpointMapper.update(null, new UpdateWrapper<AgentCheckpoint>()
                .eq("conversation_id", conversationId)
                .eq("status", "pending")
                .set("status", "expired"));
    }

    private AgentCheckpoint find(String conversationId, String formToken) {
        if (formToken == null || formToken.isBlank()) return null;
        return checkpointMapper.selectOne(new LambdaQueryWrapper<AgentCheckpoint>()
                .eq(AgentCheckpoint::getFormToken, formToken)
                .eq(AgentCheckpoint::getConversationId, conversationId)
                .last("LIMIT 1"));
    }

    private static void validateVersion(AgentCheckpoint checkpoint) {
        if ("AGENTSCOPE".equalsIgnoreCase(checkpoint.getOwnerEngine())) {
            if (checkpoint.getStateSchemaVersion() == null
                    || checkpoint.getStateSchemaVersion() != AGENTSCOPE_SCHEMA_VERSION) {
                throw new CheckpointResumeException(
                        40902, "确认已失效，请重新发起");
            }
            return;
        }
        if ("UNIFIED_LOOP".equalsIgnoreCase(checkpoint.getOwnerEngine())) {
            if (checkpoint.getStateSchemaVersion() == null
                    || checkpoint.getStateSchemaVersion() != UNIFIED_SCHEMA_VERSION) {
                throw new CheckpointResumeException(40902, "编排检查点版本不兼容，请重新发起");
            }
            return;
        }
        throw new CheckpointResumeException(40903, "旧版检查点已停用，请重新发起请求");
    }

    private static RecoveryCandidate candidate(AgentCheckpoint checkpoint) {
        return new RecoveryCandidate("checkpoint", checkpoint.getId(),
                checkpoint.getConversationId(), java.util.Map.of());
    }

    private String serialize(ResumeCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (Exception e) {
            throw new CheckpointResumeException(40001, "确认内容无法保存，请重新发起");
        }
    }

    private void hydrateRuntime(AgentCheckpoint checkpoint) {
        if (!"AGENTSCOPE".equalsIgnoreCase(checkpoint.getOwnerEngine())) return;
        AgentCheckpointRuntime runtime = checkpointMapper.selectRuntimeRef(checkpoint.getId());
        if (runtime != null) runtime.applyTo(checkpoint);
    }

    private ResumeCommand deserialize(String value) {
        if (value == null || value.isBlank()) {
            return new ResumeCommand("", "", java.util.Map.of(), java.util.List.of(), "", "");
        }
        try {
            return objectMapper.readValue(value, ResumeCommand.class);
        } catch (Exception e) {
            throw new CheckpointResumeException(40902, "确认内容无法恢复，请重新发起");
        }
    }
}
