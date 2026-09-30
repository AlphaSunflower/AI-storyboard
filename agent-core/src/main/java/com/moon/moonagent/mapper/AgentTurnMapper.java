package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.AgentTurnEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentTurnMapper extends BaseMapper<AgentTurnEntity> {

    @Select("""
            UPDATE public.agent_turns
            SET last_sequence = last_sequence + 1
            WHERE id = #{turnId}
            RETURNING last_sequence
            """)
    Integer incrementSequence(@Param("turnId") String turnId);
}

