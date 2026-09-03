package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 分镜视频批量生成队列条目（/chat Moon 智能体）。
 *
 * <p>一条记录 = 一个分镜的一个待生成视频任务。状态机：
 * queued → running → completed / failed / cancelled。
 * 终态 {@code videoUrl} / {@code videoStatus} 由 {@code VideoPollExecutor} 按 taskId 反查回写 scenes 表，
 * 本表仅跟踪队列进度、取消状态与错误信息。
 */
@Data
@TableName(value = "storyboard_video_queue", schema = "public")
public class StoryboardVideoQueue {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String sceneId;
    private String projectId;
    private String conversationId;
    private String status;
    private String prompt;
    private String model;
    private String resolution;
    private String size;
    private String aspectRatio;
    private Integer duration;
    private String negativePrompt;
    /** 分镜已生成图（作视频首帧），可为空。 */
    private String sourceImageUrl;
    /** 用户行内上传的参考图 URL（逗号分隔），可为空。 */
    private String refImageUrls;
    /** 资产库关联的资产 ID（逗号分隔），可为空；生成时取该分镜已关联的资产图作参考。 */
    private String refAssetIds;
    private String taskId;
    private Integer progress;
    private String error;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
