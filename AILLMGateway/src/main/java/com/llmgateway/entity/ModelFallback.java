package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 跨模型回退链：主模型全部渠道失败后，按 sortOrder 依次尝试 fallbackModel */
@Data
@TableName("model_fallback")
public class ModelFallback {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String modelName;
    private String type;
    private String fallbackModel;
    private Integer sortOrder;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
