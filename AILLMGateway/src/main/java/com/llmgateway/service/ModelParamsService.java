package com.llmgateway.service;

import com.llmgateway.dto.admin.ModelParamsRequest;
import com.llmgateway.entity.ModelParams;

/** 模型参数能力+默认值服务：按 model_name upsert，GET 回显（表单编辑） */
public interface ModelParamsService {

    /**
     * 按 model_name upsert：type 默认 text 并校验枚举；nMin/nMax 均提供时须 nMin<=nMax；
     * 非 null 字段更新，null 表示不修改，{@code clearFields} 里的字段显式置空；
     * 非空 paramsSchema 会先做结构校验，非法直接 40001。
     */
    ModelParams upsert(ModelParamsRequest request);

    /** 按 (model_name, type) 查询（不存在返回 null，不抛错——表单编辑回显语义；同一模型可 text/vision 各一套参数） */
    ModelParams getByModelNameAndType(String modelName, String type);

    /** 全部模型配置（模型配置工作台列表用，按 model_name/type 排序） */
    java.util.List<ModelParams> listAll();

    /** 按 model_name 查询一条配置（用于轮询时解析 Schema；同一模型多类型时取首条）。 */
    ModelParams getByModelName(String modelName);
}
