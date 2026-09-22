package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_task_edges", schema = "public")
public class AgentTaskEdge {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String planId;
    private String fromNodeId;
    private String toNodeId;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
