package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.AgentArchiveCleanup;
import com.moon.moonagent.entity.AgentConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentArchiveCleanupMapper extends BaseMapper<AgentArchiveCleanup> {

    /**
     * 取本轮待清理的已归档会话：归档时长已过宽限期，且台账里还没有清理记录。
     *
     * <p>用 LEFT JOIN 而不是「先查会话、再按 id 过滤台账」：否则 LIMIT 会被已清理的旧会话占满，
     * 后面的会话永远轮不到。
     */
    @Select("""
            SELECT c.*
            FROM public.conversations c
            LEFT JOIN public.agent_archive_cleanups a ON a.conversation_id = c.id
            WHERE c.status = 'archived'
              AND a.conversation_id IS NULL
              AND c.updated_at < now() - make_interval(days => #{graceDays})
            ORDER BY c.updated_at ASC
            LIMIT #{limit}
            """)
    List<AgentConversation> findDueForCleanup(@Param("graceDays") int graceDays,
                                              @Param("limit") int limit);
}
