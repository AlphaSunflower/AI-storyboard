package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

/** 模型参数能力+默认值表：一行一模型（model_name UNIQUE），按类型分组（text/image/video）存储能力枚举与默认值 */
@Data
@TableName("model_params")
public class ModelParams {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String modelName;
    /** 模型类型：text（文本）/ image（生图）/ video（视频生成）/ vision（图片视频理解），默认 text */
    private String type;
    // text 默认值（chat/completions）
    private String temperature;
    private Integer maxTokens;
    private String topP;
    // image 能力 + 默认（images/generations + edits 共用）
    private Integer nMin;
    private Integer nMax;
    private Integer nDefault;
    private String sizes;
    private String sizeDefault;
    private String qualities;
    private String qualityDefault;
    private String styles;
    private String styleDefault;
    // video 能力 + 默认（videos）
    private String durations;
    private String durationDefault;
    private String resolutions;
    private String resolutionDefault;
    private String aspectRatios;
    private String aspectRatioDefault;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
