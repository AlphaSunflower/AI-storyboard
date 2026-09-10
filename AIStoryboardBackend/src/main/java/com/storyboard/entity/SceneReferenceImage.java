package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "scene_reference_images", schema = "public")
public class SceneReferenceImage {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String sceneId;
    private String imageUrl;
    private Integer sortOrder;
    // 素材类型：image / video / audio（原表仅参考图，改造为通用参考素材表）
    private String type;
    // 用途：image=图片生成参考 / video=视频生成参考
    private String purpose;
    // 参考角色：FIRST_FRAME / LAST_FRAME / FIRST_LAST_FRAME / FULL_REFERENCE / REFERENCE
    private String role;
    // 来源：upload=用户直接上传 / output=项目产出素材 / scene_image=当前分镜生成图
    private String sourceType;
    // 来源对象 ID：产出记录 ID 或分镜 ID
    private String sourceId;
    // 产出素材所属会话，用于选择和查看时分组
    private String sourceConversationId;
    private String fileName;
    private Long fileSize;
}
