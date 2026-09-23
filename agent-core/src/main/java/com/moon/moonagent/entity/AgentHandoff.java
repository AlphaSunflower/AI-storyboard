package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_handoffs", schema = "public")
public class AgentHandoff {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String loopRunId;
    private String fromChain;
    private String toChain;
    private String reason;
    private String state;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
