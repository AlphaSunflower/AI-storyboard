package com.llmgateway.service;

import java.util.List;

/** 跨模型回退链：主模型全部渠道失败后按顺序尝试 fallback 模型 */
public interface ModelFallbackService {

    /** 某模型某类型的回退链（按 sortOrder） */
    List<String> list(String modelName, String type);

    /** 全量替换回退链（空列表=清空）；自动去重、过滤空值、禁止回退到自己 */
    List<String> replace(String modelName, String type, List<String> fallbacks);
}
