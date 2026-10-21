package com.moon.xiaoyuecanvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 画布执行批次（一次「执行分组」）。
 *
 * <p>执行器在服务端：批次与每个节点的状态都落库，浏览器只读快照。
 * 这样刷新页面 / HMR / 断网都不会中断工作流（以前批次活在浏览器内存里，刷新即丢）。
 */
@Data
@TableName(value = "canvas_executions", schema = "public")
public class CanvasExecution {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String canvasId;

    private String userId;

    /** queued / running / succeeded / failed / cancelled */
    private String status;

    /** 同时在跑的节点数（v1 固定 1：按依赖顺序串行） */
    private Integer maxParallel;

    private String error;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
