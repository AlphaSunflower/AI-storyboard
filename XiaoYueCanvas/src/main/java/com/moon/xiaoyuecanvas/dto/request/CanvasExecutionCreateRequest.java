package com.moon.xiaoyuecanvas.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 创建执行批次：客户端把「按画布位置排好序的节点步骤」一次性提交，服务端串行执行。
 *
 * <p>为什么由客户端给计划：节点类型/动作/结果槽/参考图绑定这些规则目前只在画布前端实现，
 * 服务端只负责「可靠地按序跑完」，不复制一套节点契约。每步的 {@code clientRequestId}
 * 让同一计划重放时不会重复生成（jobs 表唯一键幂等）。
 */
public record CanvasExecutionCreateRequest(
        Integer maxParallel,
        @NotEmpty @Valid List<Step> steps
) {
    public record Step(
            @NotBlank String nodeId,
            String actionId,
            @NotBlank String capabilityId,
            String clientRequestId,
            List<String> sourceAssetIds,
            Map<String, Object> params,
            /** 结果应该落到哪个结果槽节点（没有槽就由客户端按记录落地） */
            String resultSlotNodeId
    ) {
    }
}
