package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_loop_runs", schema = "public")
public class AgentLoopRun {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String turnId;
    private String traceId;
    private String engineVersion;
    private String currentChain;
    private String status;
    private String decision;
    private String state;
    private String checkpointId;
    private String error;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
