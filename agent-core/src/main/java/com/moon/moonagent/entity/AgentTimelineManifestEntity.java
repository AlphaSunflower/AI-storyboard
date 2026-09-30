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
@TableName(value = "agent_timeline_manifests", schema = "public", autoResultMap = true)
public class AgentTimelineManifestEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String conversationId;
    private String turnId;
    private String title;
    private String status;
    private Integer totalDurationSeconds;

    @TableField(typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String manifestJson;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
