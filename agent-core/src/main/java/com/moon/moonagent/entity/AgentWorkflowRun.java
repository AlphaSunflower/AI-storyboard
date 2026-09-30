package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_workflow_runs", schema = "public")
public class AgentWorkflowRun {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String userId;
    private String projectId;
    private String mode;
    private String status;
    private String originalPrompt;
    private String constraintJson;
    private Long version;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
    private OffsetDateTime completedAt;
}
