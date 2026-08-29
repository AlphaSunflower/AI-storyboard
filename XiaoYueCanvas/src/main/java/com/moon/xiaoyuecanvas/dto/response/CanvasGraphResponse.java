package com.moon.xiaoyuecanvas.dto.response;

import java.util.Map;

/** 画布节点图响应：graph = {nodes, edges, viewport}；revision 为当前版本（保存后/冲突时回传）。 */
public record CanvasGraphResponse(Map<String, Object> graph, int revision, String updatedAt) {
}
