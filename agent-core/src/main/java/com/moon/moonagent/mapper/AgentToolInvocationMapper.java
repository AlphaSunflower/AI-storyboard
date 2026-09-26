package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.AgentToolInvocation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;

@Mapper
public interface AgentToolInvocationMapper extends BaseMapper<AgentToolInvocation> {

    @Select("""
            SELECT id,
                   invocation_key AS invocationKey,
                   trace_id AS traceId,
                   loop_run_id AS loopRunId,
                   loop_step_id AS loopStepId,
                   task_id AS taskId,
                   conversation_id AS conversationId,
                   user_id AS userId,
                   project_id AS projectId,
                   tool_call_id AS toolCallId,
                   tool_name AS toolName,
                   tool_kind AS toolKind,
                   source,
                   status,
                   attempt,
                   input_hash AS inputHash,
                   input_summary AS inputSummary,
                   output_hash AS outputHash,
                   output_summary AS outputSummary,
                   error_code AS errorCode,
                   error_message AS errorMessage,
                   external_action_key AS externalActionKey,
                   provider_task_id AS providerTaskId,
                   result_asset_ids AS resultAssetIds,
                   expected_reference_count AS expectedReferenceCount,
                   resolved_reference_count AS resolvedReferenceCount,
                   applied_reference_count AS appliedReferenceCount,
                   skill_version AS skillVersion,
                   model,
                   engine_version AS engineVersion,
                   started_at AS startedAt,
                   completed_at AS completedAt,
                   duration_ms AS durationMs,
                   created_at AS createdAt
            FROM public.agent_tool_invocations
            WHERE invocation_key = #{invocationKey}
            """)
    AgentToolInvocation selectByInvocationKey(@Param("invocationKey") String invocationKey);

    @Delete("""
            WITH expired AS (
                SELECT id
                FROM public.agent_tool_invocations
                WHERE created_at < #{cutoff}
                ORDER BY created_at ASC
                LIMIT #{limit}
            )
            DELETE FROM public.agent_tool_invocations
            WHERE id IN (SELECT id FROM expired)
            """)
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);
}
