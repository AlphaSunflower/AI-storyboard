package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "projects", schema = "public")
public class Project {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String name;
    private String description;
    private String creationType;
    private String customTypeDesc;
    private String aspectRatio;
    private String referenceImageUrl;
    private String scriptText;
    private String aiModel;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
