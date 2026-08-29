package com.moon.xiaoyuecanvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Morrow 画布节点图（一画布一行）：整幅 {nodes, edges, viewport} 快照 + revision 乐观锁。
 * 画布元数据在 morrow_canvases；本表 canvas_id 即画布 id。
 */
@Data
@TableName(value = "morrow_canvas_graphs", schema = "public")
public class CanvasGraph {

    /** 画布 id（= morrow_canvases.id，PK 即 FK） */
    @TableId
    private String canvasId;

    /** 整幅图 JSON：{"nodes":[...],"edges":[...],"viewport":{...}}（TEXT） */
    private String graphJson;

    /** 乐观锁版本，每次成功保存 +1 */
    private Integer revision;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
