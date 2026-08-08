package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

/** 模型路由表：模型名 → 渠道映射（非唯一：一个模型可指向多个渠道按 priority 轮换） */
@Data
@TableName("model_route")
public class ModelRoute {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String modelName;
    private String channelId;
    /** JSON：size/temperature 等默认参数 */
    private String defaultParams;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableLogic
    private Boolean deleted;
}
