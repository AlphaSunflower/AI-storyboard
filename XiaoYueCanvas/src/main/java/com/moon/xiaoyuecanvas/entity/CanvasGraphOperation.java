package com.moon.xiaoyuecanvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** Durable idempotency ledger for server-side canvas graph commands. */
@Data
@TableName(value = "canvas_graph_operations", schema = "public")
public class CanvasGraphOperation {

    @TableId(value = "operation_key", type = IdType.INPUT)
    private String operationKey;

    private String canvasId;
    private String userId;
    private String projectId;
    private String runId;
    private String commandName;

    /** running | completed | failed */
    private String status;

    private String resultJson;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
