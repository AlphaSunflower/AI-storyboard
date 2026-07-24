package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    @Select("SELECT * FROM public.projects WHERE user_id = #{userId} ORDER BY updated_at DESC")
    List<Project> findByUserId(@Param("userId") String userId);

    @Select("SELECT * FROM public.projects WHERE user_id = #{userId} AND status = 'draft' ORDER BY updated_at DESC LIMIT 1")
    Project findLatestDraft(@Param("userId") String userId);
}
