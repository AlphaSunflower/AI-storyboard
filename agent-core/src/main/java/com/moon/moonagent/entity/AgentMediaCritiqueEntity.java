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
@TableName(value = "agent_media_critiques", schema = "public", autoResultMap = true)
public class AgentMediaCritiqueEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String conversationId;
    private String turnId;
    private String itemId;
    private String mediaType;
    private String assetUrl;
    private String decision;
    private Boolean hardConstraintPass;

    @TableField(typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String scoreJson;

    @TableField(typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String violationsJson;

    @TableField(typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String reasonsJson;

    private Integer repairAttempt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
