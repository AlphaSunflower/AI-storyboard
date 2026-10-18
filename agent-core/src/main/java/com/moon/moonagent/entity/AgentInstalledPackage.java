package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 动态包：kind=role 时 manifest_json 是 SubagentRole JSON；工具/技能包后续复用。
 */
@Data
@TableName(value = "agent_packages", schema = "public")
public class AgentInstalledPackage {

    @TableId(type = IdType.INPUT)
    private String id;
    private String kind;
    private String name;
    private String version;
    private String manifestJson;
    private Boolean enabled;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
