package com.llmgateway.dto.admin;

/**
 * 模型参数能力+默认值 创建/更新请求（与 ModelParams 实体字段一致，空字段表示不修改）
 */
public record ModelParamsRequest(
        String modelName,
        String type,
        String temperature,
        Integer maxTokens,
        String topP,
        Integer nMin,
        Integer nMax,
        Integer nDefault,
        String sizes,
        String sizeDefault,
        String qualities,
        String qualityDefault,
        String styles,
        String styleDefault,
        String durations,
        String durationDefault,
        String resolutions,
        String resolutionDefault,
        String aspectRatios,
        String aspectRatioDefault) {
}
