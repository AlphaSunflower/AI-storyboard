package com.moon.moonagent.ai.agent.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentToolInvocation;
import com.moon.moonagent.mapper.AgentToolInvocationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolInvocationLedger {

    private final AgentToolInvocationMapper mapper;
    private final ToolAuditSummarizer summarizer;
    private final ToolAuditProperties properties;

    public AgentToolInvocation start(ToolInvocationContext context, Object input) {
        if (!properties.isEnabled() || context == null) return null;
        try {
            String invocationKey = invocationKey(context);
            AgentToolInvocation existing = mapper.selectByInvocationKey(invocationKey);
            if (existing != null) return existing;

            AgentToolInvocation invocation = new AgentToolInvocation();
            invocation.setInvocationKey(invocationKey);
            invocation.setTraceId(blankToNull(context.getTraceId()));
            invocation.setLoopRunId(blankToNull(context.getLoopRunId()));
            invocation.setLoopStepId(blankToNull(context.getLoopStepId()));
            invocation.setTaskId(blankToNull(context.getTaskId()));
            invocation.setConversationId(blankToNull(context.getConversationId()));
            invocation.setUserId(blankToNull(context.getUserId()));
            invocation.setProjectId(blankToNull(context.getProjectId()));
            invocation.setToolCallId(blankToNull(context.getToolCallId()));
            invocation.setToolName(context.getToolName());
            invocation.setToolKind(blankToNull(context.getToolKind()));
            invocation.setSource(blankToNull(context.getSource()));
            invocation.setStatus("RUNNING");
            invocation.setAttempt(1);
            invocation.setInputHash(summarizer.hash(input));
            invocation.setInputSummary(summarizer.inputSummary(context.getToolName(), input));
            invocation.setSkillVersion(blankToNull(context.getSkillVersion()));
            invocation.setModel(blankToNull(context.getModel()));
            invocation.setEngineVersion(blankToNull(context.getEngineVersion()));
            invocation.setStartedAt(OffsetDateTime.now());
            invocation.setCreatedAt(invocation.getStartedAt());
            try {
                mapper.insert(invocation);
                return invocation;
            } catch (DuplicateKeyException duplicate) {
                return mapper.selectByInvocationKey(invocationKey);
            }
        } catch (Exception e) {
            log.warn("tool invocation ledger start failed tool={} error={}",
                    context.getToolName(), e.getMessage());
            return null;
        }
    }

    public void finish(AgentToolInvocation invocation, ToolInvocationResult result) {
        if (!properties.isEnabled() || invocation == null) return;
        try {
            OffsetDateTime completedAt = OffsetDateTime.now();
            invocation.setStatus(result == null || result.getStatus() == null
                    ? "COMPLETED" : result.getStatus());
            if (result != null) {
                invocation.setOutputHash(blankToNull(result.getOutputHash()));
                invocation.setOutputSummary(blankToNull(result.getOutputSummary()));
                invocation.setErrorCode(blankToNull(result.getErrorCode()));
                invocation.setErrorMessage(blankToNull(result.getErrorMessage()));
                invocation.setExternalActionKey(blankToNull(result.getExternalActionKey()));
                invocation.setProviderTaskId(blankToNull(result.getProviderTaskId()));
                invocation.setResultAssetIds(result.getResultAssetIds() == null
                        || result.getResultAssetIds().isEmpty()
                        ? null : String.join(",", result.getResultAssetIds()));
                invocation.setExpectedReferenceCount(result.getExpectedReferenceCount());
                invocation.setResolvedReferenceCount(result.getResolvedReferenceCount());
                invocation.setAppliedReferenceCount(result.getAppliedReferenceCount());
            }
            invocation.setCompletedAt(completedAt);
            invocation.setDurationMs(invocation.getStartedAt() == null
                    ? 0L
                    : Math.max(0L, java.time.Duration.between(
                            invocation.getStartedAt(), completedAt).toMillis()));
            mapper.updateById(invocation);
        } catch (Exception e) {
            log.warn("tool invocation ledger finish failed tool={} error={}",
                    invocation.getToolName(), e.getMessage());
        }
    }

    public List<AgentToolInvocation> list(String userId,
                                          String taskId,
                                          String conversationId,
                                          String cursor,
                                          int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        LambdaQueryWrapper<AgentToolInvocation> query = new LambdaQueryWrapper<>();
        query.eq(AgentToolInvocation::getUserId, userId);
        boolean hasTask = taskId != null && !taskId.isBlank();
        boolean hasConversation = conversationId != null && !conversationId.isBlank();
        if (hasTask && hasConversation) {
            query.and(wrapper -> wrapper
                    .eq(AgentToolInvocation::getTaskId, taskId)
                    .or(nested -> nested
                            .isNull(AgentToolInvocation::getTaskId)
                            .eq(AgentToolInvocation::getConversationId, conversationId)));
        } else if (hasTask) {
            query.eq(AgentToolInvocation::getTaskId, taskId);
        } else if (hasConversation) {
            query.eq(AgentToolInvocation::getConversationId, conversationId);
        }
        Cursor decoded = Cursor.decode(cursor);
        if (decoded != null) {
            query.and(wrapper -> wrapper
                    .lt(AgentToolInvocation::getStartedAt, decoded.startedAt())
                    .or()
                    .eq(AgentToolInvocation::getStartedAt, decoded.startedAt())
                    .lt(AgentToolInvocation::getId, decoded.id()));
        }
        query.orderByDesc(AgentToolInvocation::getStartedAt)
                .orderByDesc(AgentToolInvocation::getId)
                .last("LIMIT " + (safeLimit + 1));
        return mapper.selectList(query);
    }

    public AgentToolInvocation owned(String invocationId, String userId) {
        if (invocationId == null || invocationId.isBlank() || userId == null || userId.isBlank()) {
            return null;
        }
        AgentToolInvocation invocation = mapper.selectById(invocationId);
        return invocation != null && userId.equals(invocation.getUserId()) ? invocation : null;
    }

    public int cleanup(int limit) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(properties.getRetentionDays());
        return mapper.deleteOlderThan(cutoff, limit);
    }

    private static String invocationKey(ToolInvocationContext context) {
        if (context.getInvocationKey() != null && !context.getInvocationKey().isBlank()) {
            return context.getInvocationKey();
        }
        String trace = context.getTraceId() == null ? "" : context.getTraceId();
        String call = context.getToolCallId() == null ? "" : context.getToolCallId();
        if (!trace.isBlank() && !call.isBlank()) {
            return trace + ":" + call + ":" + context.getToolName();
        }
        return UUID.randomUUID().toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record Cursor(OffsetDateTime startedAt, String id) {
        static Cursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) return null;
            try {
                String value = new String(java.util.Base64.getUrlDecoder().decode(encoded),
                        java.nio.charset.StandardCharsets.UTF_8);
                int split = value.lastIndexOf('|');
                if (split <= 0) return null;
                return new Cursor(OffsetDateTime.parse(value.substring(0, split)),
                        value.substring(split + 1));
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
