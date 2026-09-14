package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "scenes", schema = "public")
public class Scene {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private String conversationId;
    private Integer sceneNumber;
    private String imageUrl;
    private String videoUrl;
    private String imageStatus;
    private String videoStatus;
    private String imageTaskId;
    private String videoTaskId;
    // 多图结果（逗号分隔 URL，imageUrl 保留首图兼容）
    private String imageUrls;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
