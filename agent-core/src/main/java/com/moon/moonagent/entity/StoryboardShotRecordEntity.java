package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "storyboard_shot_records", schema = "public")
public class StoryboardShotRecordEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String runId;
    private String phase;
    private String segmentId;
    private String shotId;
    private Integer shotOrdinal;
    private String status;
    private String payload;
    private String referenceAssetIds;
    private String providerTaskId;
    private String error;
    private Integer attempt;
    private String projectSceneId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
