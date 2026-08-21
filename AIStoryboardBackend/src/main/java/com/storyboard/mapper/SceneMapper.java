package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.Scene;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SceneMapper extends BaseMapper<Scene> {

    @Select("SELECT * FROM public.scenes WHERE project_id = #{projectId} ORDER BY scene_number")
    List<Scene> findByProjectIdOrdered(@Param("projectId") String projectId);

    @Select("SELECT COALESCE(MAX(scene_number), 0) FROM public.scenes WHERE project_id = #{projectId}")
    int maxSceneNumber(@Param("projectId") String projectId);

    @Select("SELECT * FROM public.scenes WHERE project_id IN (SELECT id FROM public.projects WHERE user_id = #{userId})")
    List<Scene> findByUserId(@Param("userId") String userId);

    /** 更新单个分镜的编号（排序用）。 */
    @Update("UPDATE public.scenes SET scene_number = #{sceneNumber}, updated_at = NOW() WHERE id = #{id}")
    void updateSceneNumber(@Param("id") String id, @Param("sceneNumber") int sceneNumber);

    /** 批量重置指定项目中卡在 generating 超过 5 分钟的场景状态为 failed */
    @Update("UPDATE public.scenes SET " +
            "image_status = CASE WHEN image_status = 'generating' THEN 'failed' ELSE image_status END, " +
            "video_status = CASE WHEN video_status = 'generating' THEN 'failed' ELSE video_status END, " +
            "updated_at = NOW() " +
            "WHERE project_id = #{projectId} " +
            "AND (image_status = 'generating' OR video_status = 'generating') " +
            "AND updated_at < NOW() - INTERVAL '5 minutes'")
    void resetStaleGenerating(@Param("projectId") String projectId);
}
