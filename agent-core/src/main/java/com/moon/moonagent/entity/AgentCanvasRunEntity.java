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
@TableName(value = "agent_canvas_runs", schema = "public", autoResultMap = true)
public class AgentCanvasRunEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String conversationId;
    private String userId;
    private String projectId;
    private String turnId;
    private String toolCallId;
    private String canvasId;
    private String messageId;
    private String title;
    private String status;
    private Integer progress;
    private Integer nodeCount;
    private Integer edgeCount;
    private Integer revision;

    @TableField(typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String planJson;

    private String error;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private OffsetDateTime completedAt;
}
