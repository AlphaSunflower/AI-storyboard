package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.ScenePrompt;
import org.apache.ibatis.annotations.Mapper;

/** 分镜 prompt 明细 Mapper（1:1 scene_prompts）。 */
@Mapper
public interface ScenePromptMapper extends BaseMapper<ScenePrompt> {
}
