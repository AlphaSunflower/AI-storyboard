package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.SceneReferenceImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SceneReferenceImageMapper extends BaseMapper<SceneReferenceImage> {

    @Select("SELECT * FROM public.scene_reference_images WHERE scene_id = #{sceneId} ORDER BY sort_order")
    List<SceneReferenceImage> findBySceneId(@Param("sceneId") String sceneId);
}
