package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

/** 管理后台用户表 */
@Data
@TableName("admin_user")
public class AdminUser {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String username;
    /** scrypt 哈希 */
    private String passwordHash;
    private String role;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableLogic
    private Boolean deleted;
}
