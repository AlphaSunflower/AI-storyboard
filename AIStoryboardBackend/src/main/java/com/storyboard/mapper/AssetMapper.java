package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AssetMapper extends BaseMapper<Asset> {

    /** 项目资产（project_id = 指定项目）∪ 用户全局资产（project_id IS NULL 且归属该用户）。 */
    @Select("SELECT * FROM public.assets WHERE project_id = #{projectId} " +
            "OR (project_id IS NULL AND user_id = #{userId}) ORDER BY created_at DESC")
    List<Asset> findByProjectOrGlobal(@Param("userId") String userId, @Param("projectId") String projectId);
}
