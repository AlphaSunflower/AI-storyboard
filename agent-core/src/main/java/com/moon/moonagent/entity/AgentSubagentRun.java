package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一次子智能体节点的执行审计。一个任务图 = 多行，id 为 graphId:nodeId。
 */
@Data
@TableName(value = "agent_subagent_runs", schema = "public")
public class AgentSubagentRun {

    @TableId(type = IdType.INPUT)
    private String id;
    private String graphId;
    private String conversationId;
    private String turnId;
    private String toolCallId;
    private String nodeId;
    private String role;
    private String status;
    private String instruction;
    private String taskJson;
    private String graphJson;
    private String contextRef;
    private String output;
    private String error;
    private Integer tokens;
    private Long durationMs;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
