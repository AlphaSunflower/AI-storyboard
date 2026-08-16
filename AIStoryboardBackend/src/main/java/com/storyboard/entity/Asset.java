package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * AI 资产库资产：人物/道具/场景（多图 + 文字约束）。
 * <p>project_id 为空 = 用户全局资产库；非空 = 项目资产库。
 */
@Data
@TableName(value = "assets", schema = "public")
public class Asset {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String projectId;
    /** 资产类型：character / prop / scene */
    private String type;
    private String name;
    /** 文字约束（外貌/外观/构成描述，生成时注入） */
    private String description;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
