package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 分镜 ↔ 资产关联（多对多）：分镜声明本镜出现哪些人物/道具/场景资产。
 */
@Data
@TableName(value = "scene_assets", schema = "public")
public class SceneAsset {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String sceneId;
    private String assetId;
    /** 用途：image=图片生成注入 / video=视频生成注入。 */
    private String purpose;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
