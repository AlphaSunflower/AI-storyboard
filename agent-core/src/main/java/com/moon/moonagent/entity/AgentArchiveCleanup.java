package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 已归档会话清理台账：每个会话清理成功后写一行。
 *
 * <p>作用只有一个 —— 让定时任务「每个归档会话只清一次」，避免每天重复对同一批会话
 * 做删除 + 清 AgentScope 状态 + 清沙箱的无效动作。行随会话外键级联删除。
 *
 * <p>表结构见 {@code AIStoryboardBackend/src/main/resources/db/migration/V54__agent_archive_cleanups.sql}；
 * 项目未接 Flyway，需手动执行。缺表时清理任务只记录告警并跳过，不影响其它链路。
 */
@Data
@TableName(value = "agent_archive_cleanups", schema = "public")
public class AgentArchiveCleanup {

    @TableId(type = IdType.INPUT)
    private String conversationId;

    private String userId;

    /** 本次清理删除的总行数（只统计数据库行，不含 MinIO 对象）。 */
    private Integer deletedRows;

    /** 各表删除行数的可读快照，便于排障。 */
    private String detail;

    private OffsetDateTime cleanedAt;
}
