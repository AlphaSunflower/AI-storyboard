package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

/** 业务调用 Key 表（/v1/** 静态鉴权） */
@Data
@TableName("gateway_api_key")
public class GatewayApiKey {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String name;
    /** SHA-256 哈希（明文仅签发时显示一次） */
    private String keyHash;
    private Boolean enabled;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableLogic
    private Boolean deleted;
}
