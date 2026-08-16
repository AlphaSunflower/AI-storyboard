package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.AssetImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AssetImageMapper extends BaseMapper<AssetImage> {

    /** 某资产的图集，主图在前（sort_order 升序）。 */
    @Select("SELECT * FROM public.asset_images WHERE asset_id = #{assetId} ORDER BY sort_order, created_at")
    List<AssetImage> findByAssetId(@Param("assetId") String assetId);
}
