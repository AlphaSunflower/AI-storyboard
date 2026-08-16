package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.Scene;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SceneMapper extends BaseMapper<Scene> {

    @Select("SELECT * FROM public.scenes WHERE project_id = #{projectId} ORDER BY scene_number")
    List<Scene> findByProjectIdOrdered(@Param("projectId") String projectId);

    @Select("SELECT COALESCE(MAX(scene_number), 0) FROM public.scenes WHERE project_id = #{projectId}")
    int maxSceneNumber(@Param("projectId") String projectId);

    @Select("SELECT * FROM public.scenes WHERE project_id IN (SELECT id FROM public.projects WHERE user_id = #{userId})")
    List<Scene> findByUserId(@Param("userId") String userId);
}
