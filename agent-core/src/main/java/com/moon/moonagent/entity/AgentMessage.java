package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.moon.moonagent.type.JsonbTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@TableName(value = "agent_messages", schema = "public", autoResultMap = true)
public class AgentMessage {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String role;
    private String content;
    /** 结构化元数据（HITL 卡片、reasoning、trace、suggestedActions 等）；缺失时前端仍可工作 */
    @TableField(typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> metadata;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
