package com.moon.xiaoyuecanvas.dto.request;

import java.util.Map;

/** 保存画布节点图请求：graph = {nodes, edges, viewport}；baseRevision 用于乐观锁 CAS。 */
public record CanvasGraphSaveRequest(Map<String, Object> graph, Integer baseRevision) {
}
