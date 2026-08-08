package com.llmgateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.entity.ModelParams;
import org.apache.ibatis.annotations.Mapper;

/** 模型参数能力+默认值表 Mapper */
@Mapper
public interface ModelParamsMapper extends BaseMapper<ModelParams> {}
