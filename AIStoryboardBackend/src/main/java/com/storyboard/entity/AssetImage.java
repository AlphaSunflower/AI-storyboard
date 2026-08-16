package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 资产图集：一个资产可有多张图，sort_order 最小者为「主图」（生成注入时取主图）。
 */
@Data
@TableName(value = "asset_images", schema = "public")
public class AssetImage {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String assetId;
    /** 本地相对路径 /api/files/images/xxx.png */
    private String url;
    /** 上传原始文件名（DepthCarousel 展示当前图片名用） */
    private String fileName;
    /** 排序，主图 = 最小 sort_order */
    private Integer sortOrder;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
