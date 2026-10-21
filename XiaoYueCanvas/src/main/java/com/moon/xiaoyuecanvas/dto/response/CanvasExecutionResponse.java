package com.moon.xiaoyuecanvas.dto.response;

import java.util.List;

/** 执行批次快照：前端轮询它来渲染进度，刷新后也是这样恢复。 */
public record CanvasExecutionResponse(
        String id,
        String canvasId,
        String status,
        Integer maxParallel,
        String error,
        Long createdAt,
        Long updatedAt,
        List<Node> nodes
) {
    public record Node(
            String nodeId,
            String actionId,
            String status,
            String jobId,
            List<String> resultAssetIds,
            /** 服务端已挂回画布图的画布资产 id：前端据此直接显示，不再重复物化 */
            String attachedAssetId,
            String error,
            Long startedAt,
            Long completedAt
    ) {
    }
}
