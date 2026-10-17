package com.moon.xiaoyuecanvas.service;

import java.util.List;
import java.util.Map;

/** Server-side CanvasGraphV2 command surface used by MCP tools. */
public interface CanvasGraphCommandService {

    /** 读取画布上下文（revision 与节点/连线计数）。 */
    Map<String, Object> getContext(CanvasGraphCommandContext context);

    /** 读取完整图或分页节点视图；默认紧凑视图（不含长文本 data 字段），detail=true 才回完整对象。 */
    Map<String, Object> getState(CanvasGraphCommandContext context, Integer limit, Integer offset, Boolean includeEdges, boolean detail);

    /** 列出节点：nodeIds 可选过滤，limit/offset 分页（默认 100 条），返回里带 total；默认紧凑视图。 */
    Map<String, Object> listNodes(CanvasGraphCommandContext context, List<String> nodeIds, Integer limit, Integer offset, boolean detail);

    /** 读取单个节点（完整对象）。 */
    Map<String, Object> getNode(CanvasGraphCommandContext context, String nodeId);

    /** 列出连线：edgeIds 可选过滤，limit/offset 分页（默认 100 条），返回里带 total；默认紧凑视图。 */
    Map<String, Object> listEdges(CanvasGraphCommandContext context, List<String> edgeIds, Integer limit, Integer offset, boolean detail);

    /** 列出可创建的节点类型与端口契约。 */
    Map<String, Object> listNodeTypes(CanvasGraphCommandContext context);

    /**
     * 新建单个节点。
     *
     * @param node   节点参数；可选 {@code resultSlots:{kind,count,gapY?}} 在同一个 revision 内建槽
     * @param detail true 才回显完整节点对象（默认只回 id 与计数）
     */
    Map<String, Object> createNode(CanvasGraphCommandContext context, Map<String, Object> node, boolean detail);

    /** 批量新建节点（一次 revision）；每个 item 可带 {@code resultSlots}。 */
    Map<String, Object> createNodes(CanvasGraphCommandContext context, List<Map<String, Object>> items, boolean detail);

    /** 更新节点 data 字段；detail=true 才回显完整节点对象。 */
    Map<String, Object> updateNode(CanvasGraphCommandContext context, String nodeId, Map<String, Object> patch, boolean detail);

    /** 新建单条连线；detail=true 才回显完整连线对象。 */
    Map<String, Object> createEdge(CanvasGraphCommandContext context, Map<String, Object> edge, boolean detail);

    /**
     * 批量新建连线（一次 revision）。
     *
     * <p>任一条失败则整批不落库（all-or-nothing），并在 {@code details.errors} 里返回**全部**
     * 失败项 {@code [{index, code, message}]}，让模型一轮改完。
     */
    Map<String, Object> createEdges(CanvasGraphCommandContext context, List<Map<String, Object>> edges, boolean detail);

    /** 导入单个媒体并建节点；media 可带 {@code resultSlots} 同批建槽。 */
    Map<String, Object> importMedia(CanvasGraphCommandContext context, Map<String, Object> media, boolean detail);

    /** 批量导入媒体并建节点（一次 revision）；每个 item 可带 {@code resultSlots}。 */
    Map<String, Object> importMediaBatch(CanvasGraphCommandContext context, List<Map<String, Object>> mediaItems, boolean detail);

    /** 为生产节点创建有序结果槽（名称「结果 N」在整张画布内递增）。 */
    Map<String, Object> createResultSlots(CanvasGraphCommandContext context, Map<String, Object> args, boolean detail);

    /** 把已有空媒体节点标记为结果槽。 */
    Map<String, Object> markResultSlots(CanvasGraphCommandContext context, Map<String, Object> args, boolean detail);

    /** 排布节点（grid/horizontal/vertical/dag）。 */
    Map<String, Object> layoutGraph(CanvasGraphCommandContext context, Map<String, Object> args, boolean detail);

    /**
     * 一次提交整张图的构建意图：节点 + 内联素材导入 + refs 自动连线 + 结果槽 + 布局，只产生一个 revision。
     *
     * <p>{@code refs} 是声明式引用：{@code "<ref>"} 引用该节点的输出，{@code "<ref>.result.<n>"}
     * 引用该节点第 n 个结果槽的输出；端口由 {@link CanvasNodeRegistry} 端口表自动决定。
     *
     * <p>{@code nodes[].media} 是内联素材（与 importMediaBatch 的 media item 同一实现路径）：
     * {@code {mediaId?|url?,name?,mimeType?,label?}}，mediaId 与 url 二选一；素材节点按素材类型落成
     * image/video 节点，可被其它节点的 {@code refs} 引用；同一份素材在一个 plan 里只建一个节点。
     *
     * <p><b>改图也走这里</b>：{@code updates}（改已有节点的 data，与 updateNode 同一语义）、
     * {@code markResultSlots}（把已有空媒体节点标记为某生产节点的结果槽，与 markResultSlots 同一语义）、
     * {@code createResultSlots}（给**已存在**的生产节点补建全新空槽，与 createResultSlots 同一语义）
     * 与建节点、建连线共用同一次 {@code saveGraph}，因此整批只 +1 revision。
     * 五种意图（nodes / updates / markResultSlots / createResultSlots / layout）至少给一个；
     * 纯改图与纯排版时 {@code nodes} 可以为空。
     *
     * @param plan            {@code {nodes?,edges?,updates?,markResultSlots?,createResultSlots?,layout?,pruneUnreferencedResultSlots?}}；
     *                        每个节点可选 {@code media} 与 {@code resultSlots}（两者互斥，素材节点不是生产节点）；
     *                        {@code updates[]} 形如 {@code {nodeId,patch}}；
     *                        {@code markResultSlots[]} 形如 {@code {producerNodeId,nodeIds,startOrder?}}；
     *                        {@code createResultSlots[]} 形如 {@code {producerNodeId,kind,count,position?,gapY?}}
     *                        ——注意它只作用于**已有**生产节点，与 {@code nodes[].resultSlots} 互补
     * @param continueOnError true 时跳过失败项并落库成功项（返回 errors/skipped）；
     *                        false 时汇总**全部**错误且不落库
     * @param detail          true 时额外回显完整节点/连线对象
     */
    Map<String, Object> applyPlan(CanvasGraphCommandContext context,
                                  Map<String, Object> plan,
                                  boolean continueOnError,
                                  boolean detail);
}
