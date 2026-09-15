package com.moon.xiaoyuecanvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 画布资产与 MinIO 对象的持久映射。
 *
 * <p>画布节点中的 assetId 使用本表 id；二进制文件始终位于 object_url 指向的 MinIO 对象。
 */
@Data
@TableName(value = "canvas_assets", schema = "public")
public class CanvasAsset {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String canvasId;
    private String projectId;
    private String userId;

    /** image | video | audio */
    private String type;

    private String mimeType;
    private String objectUrl;
    private String objectKey;
    private String generationRecordId;
    private String producerNodeId;

    /** pending | ready | failed */
    private String status = "ready";

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
