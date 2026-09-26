package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.AgentExternalAction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentExternalActionMapper extends BaseMapper<AgentExternalAction> {

    @Select("""
            SELECT id,
                   action_key AS actionKey,
                   conversation_id AS conversationId,
                   checkpoint_id AS checkpointId,
                   tool_call_id AS toolCallId,
                   tool_name AS toolName,
                   action_type AS actionType,
                   request_hash AS requestHash,
                   status,
                   provider_task_id AS providerTaskId,
                   response_json AS responseJson,
                   last_error AS lastError,
                   attempt,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM public.agent_external_actions
            WHERE action_key = #{actionKey}
            """)
    AgentExternalAction selectByActionKey(@Param("actionKey") String actionKey);

    @Update("""
            UPDATE public.agent_external_actions
            SET status = 'SENT',
                updated_at = now()
            WHERE action_key = #{actionKey}
              AND status = 'PREPARED'
            """)
    int markSent(@Param("actionKey") String actionKey);

    @Update("""
            UPDATE public.agent_external_actions
            SET status = #{toStatus},
                provider_task_id = COALESCE(#{providerTaskId}, provider_task_id),
                response_json = COALESCE(#{responseJson}, response_json),
                last_error = #{lastError},
                updated_at = now()
            WHERE action_key = #{actionKey}
              AND status = 'SENT'
            """)
    int finish(@Param("actionKey") String actionKey,
               @Param("toStatus") String toStatus,
               @Param("providerTaskId") String providerTaskId,
               @Param("responseJson") String responseJson,
               @Param("lastError") String lastError);
}
