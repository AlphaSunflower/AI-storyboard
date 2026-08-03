package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_assets", schema = "public")
public class AgentAsset {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String type;    // image | video | reference
    private String url;     // /api/files/images/xxx.png 或 /api/files/videos/xxx.mp4
    private String prompt;
    private String model;
    private String status;  // queued | generating | completed | failed
    private String taskId;  // 视频异步任务 ID
    private String error;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
