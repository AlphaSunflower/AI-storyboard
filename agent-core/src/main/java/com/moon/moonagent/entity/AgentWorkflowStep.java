package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_workflow_steps", schema = "public")
public class AgentWorkflowStep {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workflowRunId;
    private Integer stepIndex;
    private String stepKey;
    private String title;
    private String toolName;
    private String status;
    private String dependsOnJson;
    private String inputJson;
    private String outputJson;
    private String marker;
    private String error;
    private Integer attempts;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
}
