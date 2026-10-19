package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 账号级偏好设置。缺行时按默认值返回，首次保存时补插。
 */
@Data
@TableName(value = "user_settings", schema = "public")
public class UserSettings {
    @TableId
    private String userId;
    private String defaultApprovalMode;
    private String busySendBehavior;
    private String workStepDisplay;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
