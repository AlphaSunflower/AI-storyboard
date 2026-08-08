package com.llmgateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.entity.GatewayApiKey;
import org.apache.ibatis.annotations.Mapper;

/** 业务调用 Key 表 Mapper */
@Mapper
public interface GatewayApiKeyMapper extends BaseMapper<GatewayApiKey> {}
