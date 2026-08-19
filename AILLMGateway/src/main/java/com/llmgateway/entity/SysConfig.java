package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 系统可调配置表：key-value，替代写死在 application.yml 的 tunable 参数（修改后重启生效） */
@Data
@TableName("sys_config")
public class SysConfig {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** 配置键（唯一，如 gateway.upstream.retry-count） */
    private String configKey;
    /** 配置值（TEXT，按键语义解析为 long/int） */
    private String configValue;
    /** 中文说明（admin 回显用） */
    private String remark;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
