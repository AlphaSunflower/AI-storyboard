package com.llmgateway.service;

import com.llmgateway.dto.admin.ModelParamsRequest;
import com.llmgateway.entity.ModelParams;

/** 模型参数能力+默认值服务：按 model_name upsert，GET 回显（表单编辑） */
public interface ModelParamsService {

    /** 按 model_name upsert：type 默认 text 并校验枚举；nMin/nMax 均提供时须 nMin<=nMax；非 null 字段更新语义 */
    ModelParams upsert(ModelParamsRequest request);

    /** 按 model_name 查询（不存在返回 null，不抛错——表单编辑回显语义） */
    ModelParams getByModelName(String modelName);
}
