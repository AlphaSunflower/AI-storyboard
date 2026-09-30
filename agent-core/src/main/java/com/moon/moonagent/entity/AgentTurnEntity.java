package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@TableName(value = "agent_turns", schema = "public")
public class AgentTurnEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String conversationId;

    private String status;

    private String runtimeEngine;

    private String inputText;

    private String clientRequestId;

    private Integer lastSequence;

    private String checkpointId;

    private String error;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime startedAt;

    private OffsetDateTime completedAt;
}

