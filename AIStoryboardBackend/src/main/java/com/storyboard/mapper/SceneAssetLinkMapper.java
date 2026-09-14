package com.storyboard.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 分镜与 asset-service 资产的逻辑关联；不设置跨服务外键。 */
@Mapper
public interface SceneAssetLinkMapper {

    @Delete("DELETE FROM public.scene_asset_links WHERE scene_id = #{sceneId}")
    int deleteBySceneId(@Param("sceneId") String sceneId);

    @Insert("""
            INSERT INTO public.scene_asset_links(scene_id, asset_id, purpose)
            VALUES (#{sceneId}, #{assetId}, #{purpose})
            ON CONFLICT (scene_id, asset_id, purpose) DO NOTHING
            """)
    int insert(
            @Param("sceneId") String sceneId,
            @Param("assetId") String assetId,
            @Param("purpose") String purpose
    );

    @Select("""
            SELECT asset_id FROM public.scene_asset_links
            WHERE scene_id = #{sceneId} AND purpose = #{purpose}
            ORDER BY created_at
            """)
    List<String> findAssetIds(
            @Param("sceneId") String sceneId,
            @Param("purpose") String purpose
    );
}
