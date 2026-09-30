package com.moon.xiaoyuecanvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.xiaoyuecanvas.entity.CanvasGraphOperation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CanvasGraphOperationMapper extends BaseMapper<CanvasGraphOperation> {

    @Insert("""
            INSERT INTO public.canvas_graph_operations
                (operation_key, canvas_id, user_id, project_id, run_id, command_name, status, created_at, updated_at)
            VALUES
                (#{operationKey}, #{canvasId}, #{userId}, #{projectId}, #{runId}, #{commandName}, 'running', now(), now())
            ON CONFLICT (operation_key) DO NOTHING
            """)
    int claim(
            @Param("operationKey") String operationKey,
            @Param("canvasId") String canvasId,
            @Param("userId") String userId,
            @Param("projectId") String projectId,
            @Param("runId") String runId,
            @Param("commandName") String commandName);

    @Update("""
            UPDATE public.canvas_graph_operations
               SET status = 'completed',
                   result_json = #{resultJson},
                   updated_at = now()
             WHERE operation_key = #{operationKey}
            """)
    int complete(
            @Param("operationKey") String operationKey,
            @Param("resultJson") String resultJson,
            @Param("canvasId") String canvasId,
            @Param("userId") String userId);
}
