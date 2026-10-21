package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 站内通知：定时任务完成、失败、需要确认等事件的必达通道。 */
@Data
@TableName(value = "user_notifications", schema = "public")
public class UserNotification {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;
    /** SCHEDULE_RUN / SYSTEM */
    private String type;
    private String title;
    private String body;
    /** conversation / schedule / none */
    private String linkType;
    private String linkId;
    private String dedupeKey;
    private OffsetDateTime readAt;
    private OffsetDateTime createdAt;
}
