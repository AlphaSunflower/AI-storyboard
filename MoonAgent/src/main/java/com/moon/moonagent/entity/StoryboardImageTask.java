package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 分镜图片队列条目（/chat Moon 智能体）。
 *
 * <p>一条记录 = 一个分镜的一次图改图任务。状态机：
 * queued → running → completed / failed / cancelled。
 * 终态图片 URL / {@code scenes.imageStatus} 由 {@code ImageGenerationServiceImpl} 按 sceneId 回写，
 * 本表仅跟踪队列进度、取消状态与错误信息。
 */
@Data
@TableName(value = "storyboard_image_task", schema = "public")
public class StoryboardImageTask {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String sceneId;
    private String projectId;
    private String conversationId;
    private String status;
    private String prompt;
    private String model;
    private String size;
    private String quality;
    private Integer n;
    /** 生成模式：edit=完善已有图片，generate=自动生成分镜图片。 */
    private String mode;
    /** 当前分镜已生成图片 URL（编辑源图），可为空（为空则后端读 scene.imageUrl）。 */
    private String sourceImageUrl;
    /** 用户行内上传的参考图 URL（逗号分隔），可为空。 */
    private String refImageUrls;
    private String taskId;
    private Integer progress;
    private String error;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
