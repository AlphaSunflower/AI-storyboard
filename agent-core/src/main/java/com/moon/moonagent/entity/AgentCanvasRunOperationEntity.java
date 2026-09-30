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
@TableName(value = "agent_canvas_run_operations", schema = "public", autoResultMap = true)
public class AgentCanvasRunOperationEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String runId;
    private String operationKey;

    @TableField(typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String resultJson;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
