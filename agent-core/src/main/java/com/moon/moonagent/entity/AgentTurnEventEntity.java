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
@TableName(value = "agent_turn_events", schema = "public")
public class AgentTurnEventEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String turnId;

    private Integer sequence;

    private String eventType;

    private String payload;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}

