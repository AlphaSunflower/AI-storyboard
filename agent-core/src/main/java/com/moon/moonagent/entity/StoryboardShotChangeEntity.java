package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 分镜草稿字段级改动日志。一行 = 某个镜头某个字段的一次变化。
 *
 * <p>列名刻意避开 SQL 关键字：{@code field} 在部分方言里是保留字，这里映射成 {@code field} 列名，
 * 由 MyBatis-Plus 原样引用（PostgreSQL 允许非保留字的 field）。
 */
@Data
@TableName(value = "storyboard_shot_changes", schema = "public")
public class StoryboardShotChangeEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String runId;
    private String shotId;
    private Integer shotOrdinal;
    /** INSERT / UPDATE / DELETE */
    private String kind;
    /** user / agent / system */
    private String source;
    /** 中文字段名（画面/地点/时间/角色/资产） */
    private String field;
    private String beforeValue;
    private String afterValue;
    private OffsetDateTime createdAt;
}
