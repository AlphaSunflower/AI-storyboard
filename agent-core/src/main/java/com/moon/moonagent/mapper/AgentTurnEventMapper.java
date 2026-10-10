package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.AgentTurnEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentTurnEventMapper extends BaseMapper<AgentTurnEventEntity> {

    @Insert("""
            <script>
            INSERT INTO public.agent_turn_events
                (id, turn_id, sequence, event_type, payload, created_at)
            VALUES
            <foreach collection="events" item="event" separator=",">
                (#{event.id}, #{event.turnId}, #{event.sequence}, #{event.eventType},
                 #{event.payload}, #{event.createdAt})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("events") List<AgentTurnEventEntity> events);
}

