package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@TableName(value = "storage_cleanup_queue", schema = "public")
public class StorageCleanupTask {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String objectUrl;
    private String status;
    private Integer attempts;
    private OffsetDateTime nextAttemptAt;
    private String lastError;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
