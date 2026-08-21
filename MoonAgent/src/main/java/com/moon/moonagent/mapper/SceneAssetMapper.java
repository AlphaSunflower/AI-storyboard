package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.SceneAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SceneAssetMapper extends BaseMapper<SceneAsset> {

    /** 某分镜关联的资产关联记录。 */
    @Select("SELECT * FROM public.scene_assets WHERE scene_id = #{sceneId} ORDER BY created_at")
    List<SceneAsset> findBySceneId(@Param("sceneId") String sceneId);
}
