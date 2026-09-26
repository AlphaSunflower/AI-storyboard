package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_recovery_leases", schema = "public")
public class AgentRecoveryLease {

    @TableId(type = IdType.INPUT)
    private String resourceType;
    private String resourceId;
    private String leaseOwner;
    private OffsetDateTime leaseUntil;
    private Integer attempt;
    private OffsetDateTime nextAttemptAt;
    private String lastError;
    private OffsetDateTime updatedAt;
}
