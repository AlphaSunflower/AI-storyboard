package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_async_tasks", schema = "public")
public class AgentAsyncTask {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String userId;
    private String sessionId;
    private String toolCallId;
    private String toolName;
    private String kind;
    private String status;
    private String title;
    private String stage;
    private Integer progress;
    private String requestJson;
    private String resultJson;
    private String resultSummary;
    private String assetIdsJson;
    private String resultUrlsJson;
    private String messageId;
    private String anchorMessageId;
    private String parentTaskId;
    private Long version;
    private Boolean cancelRequested;
    private OffsetDateTime contextConsumedAt;
    private String wakeupStatus;
    private OffsetDateTime wakeupEnqueuedAt;
    private OffsetDateTime wakeupConsumedAt;
    private String wakeupMessageId;
    private String wakeupError;
    private Integer wakeupAttempts;
    private String error;
    private String idempotencyKey;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime completedAt;
}
