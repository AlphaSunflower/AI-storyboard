package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 帮助与反馈记录：正文、截图相对路径（逗号分隔）和处理状态。
 */
@Data
@TableName(value = "user_feedback", schema = "public")
public class UserFeedback {
    @TableId
    private String id;
    private String userId;
    private String content;
    /** `/api/files/images/xxx.png` 逗号分隔；不保存签名 query。 */
    private String imageUrls;
    private String status;
    private OffsetDateTime createdAt;

    /** 管理员收件箱展示用，来自 users 表 join，不落库。 */
    @TableField(exist = false)
    private String userEmail;
    @TableField(exist = false)
    private String userDisplayName;
}
