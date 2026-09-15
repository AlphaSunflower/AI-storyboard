package com.moon.xiaoyuecanvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 追加写作业事件；唯一键为 job_id + seq。 */
@Data
@TableName(value = "canvas_job_events", schema = "public")
public class CanvasJobEvent {

    private String jobId;

    private Long seq;

    private String type;

    /** 已序列化的 JSON payload。 */
    private String payload;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
