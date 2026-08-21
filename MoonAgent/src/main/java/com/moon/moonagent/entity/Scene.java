package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "scenes", schema = "public")
public class Scene {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private Integer sceneNumber;
    private String scriptContent;
    private String imagePrompt;
    private String videoPrompt;
    private String negativePrompt;
    private String cameraMovement;
    private String shotType;
    private String soundDesign;
    private String aiModel;
    private String videoResolution;
    private Integer duration;
    private String imageUrl;
    private String videoUrl;
    private String imageStatus;
    private String videoStatus;
    private String imageTaskId;
    private String videoTaskId;
    // 分镜生成参数覆盖（null = 跟随全局默认）+ 多图结果（逗号分隔 URL，imageUrl 保留首图兼容）
    private String imageUrls;
    private String imageModel;
    private String imageSize;
    private String imageQuality;
    private Integer imageN;
    private String videoModel;
    private String videoAspectRatio;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
