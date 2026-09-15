package com.moon.xiaoyuecanvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.xiaoyuecanvas.entity.CanvasJobEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CanvasJobEventMapper extends BaseMapper<CanvasJobEvent> {

    @Select("""
            SELECT job_id, seq, type, payload, created_at
            FROM public.canvas_job_events
            WHERE job_id = #{jobId}
              AND seq > #{afterSeq}
              AND seq <= #{cursorSeq}
            ORDER BY seq
            """)
    List<CanvasJobEvent> selectRange(@Param("jobId") String jobId,
                                     @Param("afterSeq") long afterSeq,
                                     @Param("cursorSeq") long cursorSeq);
}
