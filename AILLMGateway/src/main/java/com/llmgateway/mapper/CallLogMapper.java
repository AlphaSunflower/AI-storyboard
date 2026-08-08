package com.llmgateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.entity.CallLog;
import org.apache.ibatis.annotations.Mapper;

/** 调用日志表 Mapper */
@Mapper
public interface CallLogMapper extends BaseMapper<CallLog> {}
