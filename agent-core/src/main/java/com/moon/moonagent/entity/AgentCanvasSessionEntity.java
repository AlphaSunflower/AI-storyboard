package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.moon.moonagent.type.JsonbStringTypeHandler;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

import java.time.OffsetDateTime;

@Getter
@Setter
@TableName(value = "agent_canvas_sessions", schema = "public", autoResultMap = true)
public class AgentCanvasSessionEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String conversationId;
    private String userId;
    private String projectId;
    private String turnId;
    private String runtimeReplyId;
    private String runtimeToolCallId;
    private String canvasId;
    private String canvasTitle;
    private String intent;
    private String status;

    @TableField(typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String choicesJson;

    @TableField(typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String mediaRefsJson;

    private String error;
    private OffsetDateTime expiresAt;
    private OffsetDateTime readyAt;
    private OffsetDateTime resumedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
