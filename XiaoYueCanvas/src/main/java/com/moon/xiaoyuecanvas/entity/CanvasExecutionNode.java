package com.moon.xiaoyuecanvas.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 执行批次里的一个节点步骤。
 *
 * <p>`params` / `sourceAssetIds` / `resultAssetIds` 以 TEXT 存 JSON（同 canvas_jobs 的做法）。
 * `clientRequestId` 是提交 job 的幂等键：服务重启后重放同一步不会重复生成。
 */
@Data
@TableName(value = "canvas_execution_nodes", schema = "public")
public class CanvasExecutionNode {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String executionId;

    /** 批次内执行顺序（客户端按画布位置排好的序号） */
    private Integer position;

    private String nodeId;

    private String actionId;

    private String capabilityId;

    private String clientRequestId;

    /** 提交 job 的 params（JSON TEXT） */
    private String params;

    /** 参考素材 id（JSON TEXT） */
    private String sourceAssetIds;

    /** 结果要落到哪个结果槽节点（没有槽时为 null，由客户端按记录落地） */
    private String resultSlotNodeId;

    /** pending / running / succeeded / failed / blocked / cancelled */
    private String status;

    private String jobId;

    /** 本次产出的素材 id（JSON TEXT） */
    private String resultAssetIds;

    /** 服务端挂回画布图时使用的画布资产 id（canvas_assets.id） */
    private String attachedAssetId;

    private String error;

    private OffsetDateTime startedAt;

    private OffsetDateTime completedAt;
}
