package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 记忆管理总开关（按账号）。
 *
 * <p>缺行视为启用，兼容开关上线前的账号；关闭后智能体不能写/召回记忆，
 * 用户不能修改全局记忆，但仍可删除既有记忆。
 */
@Data
@TableName(value = "agent_memory_settings", schema = "public")
public class AgentMemorySetting {

    @TableId(type = IdType.INPUT)
    private String userId;
    private Boolean enabled;
    private OffsetDateTime updatedAt;
}
