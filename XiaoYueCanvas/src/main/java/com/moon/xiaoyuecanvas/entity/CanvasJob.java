package com.moon.xiaoyuecanvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 设计助理作业记录（存 ai_storyboard 共享库）。
 * Morrow 契约：{@code POST /jobs} → {@code jobId}；{@code clientRequestId} 为幂等键。
 * {@code params} 等结构化字段以 TEXT 存 JSON（同 AgentCheckpoint.plan 做法，规避 jsonb 类型处理）。
 */
@Data
@TableName(value = "canvas_jobs", schema = "public")
public class CanvasJob {

    /** 作业 id（服务端生成） */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 幂等键（客户端 uuid，唯一） */
    private String clientRequestId;

    /** 作业属主（JWT userId） */
    private String userId;

    /** 归属会话（可为空；同 conversational model，防 IDOR 归属校验用） */
    private String conversationId;

    /** 画布项目 id（作用域） */
    private String projectId;

    /** 能力 id（agent.request / image.generate / image.redraw / video.generate …） */
    private String capabilityId;

    /** 上下文 nodeIds（JSON TEXT） */
    private String nodeIds;

    /** 选中节点 id */
    private String selectedNodeId;

    /** 源素材 id 列表（JSON TEXT） */
    private String sourceAssetIds;

    /** 参数（JSON TEXT：prompt/model/resolution/duration/size/quality 等） */
    private String params;

    /** queued / running / completed / failed / cancelled */
    private String status;

    /** 进度（0-100） */
    private Integer progress;

    /** 失败原因（脱敏） */
    private String error;

    /** 结果素材 id（→ agent_assets.id） */
    private String resultAssetId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
