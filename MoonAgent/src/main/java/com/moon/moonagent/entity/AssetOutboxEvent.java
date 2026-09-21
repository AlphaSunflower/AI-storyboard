package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 生成结果进入项目库的持久化 outbox。 */
@Data
@TableName(value = "asset_outbox", schema = "public")
public class AssetOutboxEvent {

    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;
    private String producer;
    private String generationRecordId;
    private Integer resultIndex;
    private String groupId;
    private String resultAssetId;
    private String payloadJson;
    private String status;
    private Integer attemptCount;
    private String errorMessage;
    private OffsetDateTime nextRetryAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
