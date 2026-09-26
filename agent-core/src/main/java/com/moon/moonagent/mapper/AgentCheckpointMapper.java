package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentCheckpointRuntime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentCheckpointMapper extends BaseMapper<AgentCheckpoint> {

    @Update("""
            UPDATE public.agent_checkpoints
            SET runtime_session_id = #{checkpoint.runtimeSessionId},
                runtime_reply_id = #{checkpoint.runtimeReplyId},
                runtime_tool_call_id = #{checkpoint.runtimeToolCallId},
                runtime_tool_name = #{checkpoint.runtimeToolName},
                runtime_tool_calls_json = #{checkpoint.runtimeToolCallsJson},
                resume_payload = COALESCE(#{checkpoint.resumePayload}, resume_payload),
                updated_at = now()
            WHERE id = #{checkpoint.id}
            """)
    int updateRuntimeRef(@Param("checkpoint") AgentCheckpoint checkpoint);

    @Select("""
            SELECT runtime_session_id AS runtimeSessionId,
                   runtime_reply_id AS runtimeReplyId,
                   runtime_tool_call_id AS runtimeToolCallId,
                   runtime_tool_name AS runtimeToolName,
                   runtime_tool_calls_json AS runtimeToolCallsJson,
                   resume_payload AS resumePayload
            FROM public.agent_checkpoints
            WHERE id = #{id}
            """)
    AgentCheckpointRuntime selectRuntimeRef(@Param("id") String id);

    @Update("""
            UPDATE public.agent_checkpoints AS checkpoint
            SET status = #{toStatus},
                updated_at = now()
            WHERE checkpoint.id = #{id}
              AND checkpoint.status IN ('claimed', 'pending')
              AND EXISTS (
                  SELECT 1
                  FROM public.agent_recovery_leases AS lease
                  WHERE lease.resource_type = 'checkpoint'
                    AND lease.resource_id = checkpoint.id
                    AND lease.lease_owner = #{owner}
                    AND lease.lease_until > now()
              )
            """)
    int transitionIfLeased(@Param("id") String id,
                           @Param("toStatus") String toStatus,
                           @Param("owner") String owner);
}
