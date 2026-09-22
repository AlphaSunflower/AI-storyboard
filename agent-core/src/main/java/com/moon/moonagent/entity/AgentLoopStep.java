package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_loop_steps", schema = "public")
public class AgentLoopStep {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String loopRunId;
    private String planId;
    private String nodeId;
    private Integer attemptNo;
    private Integer stepIndex;
    private String chain;
    private String phase;
    private String status;
    private String decision;
    private String inputSummary;
    private String outputSummary;
    private String toolCallId;
    private String jobId;
    private String error;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
}
