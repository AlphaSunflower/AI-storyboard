package com.moon.moonagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * AI Agent HITL 人工确认 checkpoint（表 agent_checkpoints）。
 *
 * 替代原 form 快照内存 Map（formSnapshots/videoPlanSnapshots，重启即失）：
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
    /** 创建该检查点的引擎：LEGACY | UNIFIED_LOOP。恢复必须按 owner 路由。 */
    private String ownerEngine;
    /** checkpoints.plan 的状态协议版本。 */
    private Integer stateSchemaVersion;
    /** 动态任务计划归属（旧 checkpoint 为空）。 */
    private String planId;
    /** 动态任务节点归属（旧 checkpoint 为空）。 */
    private String nodeId;
    /** pending | used | expired */
    private String status;
    /** 过期时间（创建 + 30min，对齐原 FORM_SNAPSHOT_TTL_MS） */
    private OffsetDateTime expirationTime;
    /** AgentScope 会话标识；v3 固定为 conversation_id。 */
    @TableField(exist = false)
    private String runtimeSessionId;
    /** AgentScope 可恢复回复标识。 */
    @TableField(exist = false)
    private String runtimeReplyId;
    /** 触发暂停的工具调用标识。 */
    @TableField(exist = false)
    private String runtimeToolCallId;
    /** 触发暂停的工具名。 */
    @TableField(exist = false)
    private String runtimeToolName;
    /** 权限确认包含的完整工具调用列表。 */
    @TableField(exist = false)
    private String runtimeToolCallsJson;
    /** 用户提交的续跑命令（action/customText/params/assetIds/routingHint 等）。 */
    @TableField(exist = false)
    private String resumePayload;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
