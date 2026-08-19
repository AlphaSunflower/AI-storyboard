package com.llmgateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;

/** 系统可调配置表 Mapper */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}
