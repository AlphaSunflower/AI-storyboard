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
    private String userId;
    private String projectId;
    private String type;    // image | video | reference
    private String url;     // /api/files/images/xxx.png 或 /api/files/videos/xxx.mp4
    private String prompt;
    private String model;
    private String status;  // queued | generating | completed | failed
    private String taskId;  // 视频异步任务 ID
    private String error;
    // ── 生成记录扩展 ──
    private String genType;          // text_to_image | image_to_image | multi_image_to_image | multi_image_to_multi_image | text_to_video | ref_to_video | first_frame_to_video | first_last_frame_to_video
    private String params;           // 生成参数 JSON 字符串
    private String referenceUrls;    // 参考素材 JSON 字符串 [{type,url,role?}]
    private String resultUrls;       // 结果 URL 数组 JSON 字符串
    private Long durationMs;         // 尝试开始到终态耗时（毫秒）
    private String sceneId;          // 命中真实分镜时记录 sceneId
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
