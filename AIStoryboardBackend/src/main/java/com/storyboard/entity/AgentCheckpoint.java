package com.storyboard.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * AI Agent HITL 人工确认 checkpoint（表 agent_checkpoints）。
 *
 * 替代原 Dify form 快照内存 Map（formSnapshots/videoPlanSnapshots，重启即失）：
 * 编排流程需要人工确认时落一行，表单提交端点校验 form_token + 归属 + 未过期后
 * 置为 used 并恢复对应 step 执行。
 */
@Data
@TableName(value = "agent_checkpoints", schema = "public")
public class AgentCheckpoint {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String conversationId;
    /** 确认动作：agree | disagree | generate_image | generate_video | refine | done */
    private String action;
    /** resume token（一次性，前端 form/submit 传 formToken、video/plan/generate 传 planToken） */
    private String formToken;
    /** 方案快照 JSON（原 lastNodeOutputs 内容：分镜 items / 图片方案 / 视频方案） */
    private String plan;
    /** 编排状态名（SCRIPT_OPTIMIZE / STORYBOARD_PLAN / ... 便于恢复对应 step） */
    private String step;
    /** pending | used | expired */
    private String status;
    /** 过期时间（创建 + 30min，对齐原 FORM_SNAPSHOT_TTL_MS） */
    private OffsetDateTime expirationTime;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
