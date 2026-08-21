package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_messages", schema = "public")
public class AgentMessage {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String role;
    private String content;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}