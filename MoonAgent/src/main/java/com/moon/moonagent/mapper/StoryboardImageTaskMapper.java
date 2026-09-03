package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.StoryboardImageTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StoryboardImageTaskMapper extends BaseMapper<StoryboardImageTask> {

    /** 按项目查询队列（created_at 正序）。 */
    @Select("SELECT * FROM public.storyboard_image_task WHERE project_id = #{projectId} ORDER BY created_at")
    List<StoryboardImageTask> findByProjectId(@Param("projectId") String projectId);

    /** 按项目查询进行中队列（queued/running，created_at 正序）。 */
    @Select("SELECT * FROM public.storyboard_image_task WHERE project_id = #{projectId} AND status IN ('queued', 'running') ORDER BY created_at")
    List<StoryboardImageTask> findActiveByProject(@Param("projectId") String projectId);

    /** 下一条待执行（queued）任务，按入队时间正序。 */
    @Select("SELECT * FROM public.storyboard_image_task WHERE status = 'queued' ORDER BY created_at LIMIT 1")
    StoryboardImageTask findNextQueued();
}
