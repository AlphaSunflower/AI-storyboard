package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.AgentMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {

    /**
     * 按客户端幂等 ID 查找已落库的 steer 消息。
     *
     * <p>显式 SQL 而不是 Wrapper：JSONB 的 {@code ->>} 在 Wrapper 的 apply 里
     * 容易和列名解析/参数绑定混在一起，实测会查不到已存在的行，导致重复落库。
     */
    @Select("""
            SELECT * FROM public.agent_messages
            WHERE conversation_id = #{conversationId}
              AND metadata->>'clientMessageId' = #{clientMessageId}
            LIMIT 1
            """)
    AgentMessage findSteerByClientMessageId(@Param("conversationId") String conversationId,
                                            @Param("clientMessageId") String clientMessageId);
}
