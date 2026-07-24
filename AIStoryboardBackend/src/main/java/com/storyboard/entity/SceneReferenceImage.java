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
}
