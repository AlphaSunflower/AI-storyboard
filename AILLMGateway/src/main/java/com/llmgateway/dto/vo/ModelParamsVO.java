package com.llmgateway.dto.vo;

import java.time.OffsetDateTime;

/** 模型参数能力+默认值视图对象（对应实体 ModelParams，无敏感字段，全量透出） */
public record ModelParamsVO(
        String id,
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
        String aspectRatioDefault,
        Integer refImagesMin, Integer refImagesMax,
        Integer refVideosMin, Integer refVideosMax,
        Integer audioCountMin, Integer audioCountMax,
        Integer audioSegmentDurationMin, Integer audioSegmentDurationMax,
        Integer videoSegmentDurationMin, Integer videoSegmentDurationMax,
        Integer maxTotalDuration, Integer maxTotalFiles,
        Integer maxVideoSizeMb, Integer maxImageSizeMb, Integer maxAudioSizeMb,
        Integer maxRequestBodyMb, Integer maxPromptChars,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
