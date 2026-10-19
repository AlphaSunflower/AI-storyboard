package com.llmgateway.dto.admin;

import java.util.List;

/**
 * 模型参数能力+默认值 创建/更新请求（与 ModelParams 实体字段一致）。
 *
 * <p>字段为 null 表示「不修改」；要清空已保存的值，把字段名放进 {@link #clearFields}
 * （例如 {@code ["paramsSchema","sizes"]}）。空字符串的 paramsSchema / ollamaParams 也按清空处理。
 */
public record ModelParamsRequest(
        String modelName,
        String type,
        Boolean isDefault,
        String temperature,
        Integer maxTokens,
        String topP,
        Integer contextWindow,
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
        String aspectRatioDefault,
        Integer refImagesMin, Integer refImagesMax,
        Integer refVideosMin, Integer refVideosMax,
        Integer audioCountMin, Integer audioCountMax,
        Integer audioSegmentDurationMin, Integer audioSegmentDurationMax,
        Integer videoSegmentDurationMin, Integer videoSegmentDurationMax,
        Integer maxTotalDuration, Integer maxTotalFiles,
        Integer maxVideoSizeMb, Integer maxImageSizeMb, Integer maxAudioSizeMb,
        Integer maxRequestBodyMb, Integer maxPromptChars,
        String ollamaParams,
        String paramsSchema,
        List<String> clearFields) {
}
