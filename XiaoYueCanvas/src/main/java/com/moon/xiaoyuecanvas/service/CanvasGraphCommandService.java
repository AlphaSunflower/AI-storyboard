package com.moon.xiaoyuecanvas.service;

import java.util.List;
import java.util.Map;

/** Server-side CanvasGraphV2 command surface used by MCP tools. */
public interface CanvasGraphCommandService {

    Map<String, Object> getContext(CanvasGraphCommandContext context);

    Map<String, Object> getState(CanvasGraphCommandContext context, Integer limit, Integer offset, Boolean includeEdges);

    Map<String, Object> listNodes(CanvasGraphCommandContext context, List<String> nodeIds);

    Map<String, Object> getNode(CanvasGraphCommandContext context, String nodeId);

    Map<String, Object> listEdges(CanvasGraphCommandContext context, List<String> edgeIds);

    Map<String, Object> listNodeTypes(CanvasGraphCommandContext context);

    Map<String, Object> createNode(CanvasGraphCommandContext context, Map<String, Object> node);

    Map<String, Object> createNodes(CanvasGraphCommandContext context, List<Map<String, Object>> items);

    Map<String, Object> updateNode(CanvasGraphCommandContext context, String nodeId, Map<String, Object> patch);

    Map<String, Object> createEdge(CanvasGraphCommandContext context, Map<String, Object> edge);

    Map<String, Object> importMedia(CanvasGraphCommandContext context, Map<String, Object> media);

    Map<String, Object> importMediaBatch(CanvasGraphCommandContext context, List<Map<String, Object>> mediaItems);

    Map<String, Object> createResultSlots(CanvasGraphCommandContext context, Map<String, Object> args);

    Map<String, Object> markResultSlots(CanvasGraphCommandContext context, Map<String, Object> args);

    Map<String, Object> layoutGraph(CanvasGraphCommandContext context, Map<String, Object> args);
}
