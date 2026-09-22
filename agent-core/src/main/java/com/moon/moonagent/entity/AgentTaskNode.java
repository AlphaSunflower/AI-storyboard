package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_task_nodes", schema = "public")
public class AgentTaskNode {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String planId;
    private String nodeId;
    private String capabilityKey;
    private String title;
    private String state;
    private Integer attemptCount;
    private String hitlPolicy;
    private String evaluationMode;
    private String qualityCriteria;
    private String outputSummary;
    private String asyncTaskId;
    private String error;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
