package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_assets", schema = "public")
public class AgentAsset {

    /**
     * 软删除标记。用户删除素材时 MinIO 对象即刻进清理队列，但行本身保留
     * （提示词、参数、结果 URL 仍要能在「生成记录」里查阅），只把 status 置成这个值。
     * 会话资产列表按它过滤；生成记录照常展示并标注「已删除」。
     */
    public static final String STATUS_DELETED = "deleted";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    private String userId;
    private String projectId;
    private String type;    // image | video | reference
    private String url;     // /api/files/images/xxx.png 或 /api/files/videos/xxx.mp4
    private String originalName; // 上传时的原始文件名
    private String mimeType;     // 上传时的 MIME 类型
    private String prompt;
    private String model;
    private String purpose; // 用户上传素材时填写的用途/说明（人物/场景/风格参考等）
    /**
     * 视觉模型生成的素材语义描述（「角色｜林知夏：短发女性，黑色风衣…」）。
     * 上传后由 AssetCaptionService 异步写入；SessionAssetService 的 label 优先读它，
     * 否则模型只会看到「会话图片」，无法按剧本把对应资产绑到分镜。
     */
    private String description;
    private String kind;    // reference | asset（asset=带文字约束，无需整理智能体再次分类）
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
    private String loopRunId;        // UnifiedLoop 运行 ID
    private String chainRunId;       // 链路执行 ID
    private String planId;           // 动态任务计划 ID
    private String nodeId;           // 动态任务节点 ID
    private String idempotencyKey;   // 跨请求去重键
    private String requestFingerprint; // 请求指纹，防同键不同参数
    private String engineVersion;    // 创建记录的引擎版本
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
