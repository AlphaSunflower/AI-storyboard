package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_task_plans", schema = "public")
public class AgentTaskPlan {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String goal;
    private String status;
    private Integer version;
    private String planJson;
    private String planSignature;
    private Integer maxNodes;
    private Integer maxAttemptsPerNode;
    private Integer maxReplans;
    private Integer replanCount;
    private String error;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
