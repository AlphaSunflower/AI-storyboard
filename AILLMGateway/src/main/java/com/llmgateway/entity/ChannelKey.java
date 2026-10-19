package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 渠道 Key 池：一个渠道可挂多个 Key，按权重轮换，单 Key 失败冷却 */
@Data
@TableName("channel_key")
public class ChannelKey {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String channelId;
    private String label;
    /** AES 加密密文（与 channel.api_key 同算法） */
    private String apiKey;
    private Integer weight;
    private Boolean enabled;
    private Integer failCount;
    private OffsetDateTime cooldownUntil;
    private OffsetDateTime lastUsedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
