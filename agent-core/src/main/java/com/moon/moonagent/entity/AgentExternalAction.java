package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_external_actions", schema = "public")
public class AgentExternalAction {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String actionKey;
    private String conversationId;
    private String checkpointId;
    private String toolCallId;
    private String toolName;
    private String actionType;
    private String requestHash;
    private String status;
    private String providerTaskId;
    private String responseJson;
    private String lastError;
    private Integer attempt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
