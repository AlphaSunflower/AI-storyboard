package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_tool_invocations", schema = "public")
public class AgentToolInvocation {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String invocationKey;
    private String traceId;
    private String loopRunId;
    private String loopStepId;
    private String taskId;
    private String conversationId;
    private String userId;
    private String projectId;
    private String toolCallId;
    private String toolName;
    private String toolKind;
    private String source;
    private String status;
    private Integer attempt;
    private String inputHash;
    private String inputSummary;
    private String outputHash;
    private String outputSummary;
    private String errorCode;
    private String errorMessage;
    private String externalActionKey;
    private String providerTaskId;
    private String resultAssetIds;
    private Integer expectedReferenceCount;
    private Integer resolvedReferenceCount;
    private Integer appliedReferenceCount;
    private String skillVersion;
    private String model;
    private String engineVersion;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private Long durationMs;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
