package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "storyboard_runs", schema = "public")
public class StoryboardRunEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String projectId;
    private String conversationId;
    private String canvasId;
    private String originalScript;
    private String selectedScript;
    private String expansionScript;
    private String stage;
    private Integer version;
    private String plotAssessment;
    private String storyBible;
    private String roles;
    private String assets;
    private String pseudoShots;
    private String finalShots;
    private String error;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
