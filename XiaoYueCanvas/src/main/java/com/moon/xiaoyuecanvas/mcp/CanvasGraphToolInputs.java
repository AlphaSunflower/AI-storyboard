package com.moon.xiaoyuecanvas.mcp;

import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * canvas_graph_* 工具的类型化业务入参。
 *
 * <p>用 record 而不是自由 {@code Map<String,Object>}：Spring AI 会据此生成**字段级 JSON Schema**，
 * 模型能直接看到每个键的名称、类型、必填性与含义，不必再猜（历史事故：模型把 edge 的字段名
 * 猜错、被服务端拒绝，白花一整轮）。
 *
 * <p>注入型参数（userId/projectId/canvasId/runId/operationKey）仍留在工具方法签名里，由 MoonAgent
 * 的 {@code CanvasRunToolAdapter} 在模型调用之后注入，模型不需要也不应该传。
 *
 * <p>{@code toMap()} 只写入非 null 字段：避免"模型没传的字段"被当成显式置空。
 */
public final class CanvasGraphToolInputs {

    private CanvasGraphToolInputs() {
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static void putAll(Map<String, Object> target, Map<String, Object> values) {
        if (values == null) return;
        values.forEach((key, value) -> put(target, key, value));
    }

    /** 节点坐标（画布世界坐标，单位 px）。 */
    public record Position(
            @ToolParam(description = "X 坐标（px），正数向右", required = true)
            @McpToolParam(description = "X 坐标（px），正数向右", required = true) Double x,
            @ToolParam(description = "Y 坐标（px），正数向下", required = true)
            @McpToolParam(description = "Y 坐标（px），正数向下", required = true) Double y) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "x", x);
            put(out, "y", y);
            return out;
        }
    }

    /**
     * 结果槽声明（可选）：节点建好后立刻在它右侧建 N 个空图片/视频占位并连回该节点。
     *
     * <p>结果槽是「生产节点 → 结果槽 → 下游」链路的中间占位，名称「结果 N」由服务端在整张画布内
     * 递增生成，不要自己指定 label。
     */
    public record ResultSlotSpec(
            @ToolParam(description = "槽位媒体类型：image 或 video；省略时按该节点唯一的图片/视频输出推断", required = false)
            @McpToolParam(description = "槽位媒体类型：image 或 video；省略时按该节点唯一的图片/视频输出推断", required = false)
            String kind,
            @ToolParam(description = "槽位数量 1~12，默认 1；用户说 n 张/几个版本时按该数量", required = false)
            @McpToolParam(description = "槽位数量 1~12，默认 1；用户说 n 张/几个版本时按该数量", required = false)
            Integer count,
            @ToolParam(description = "槽位起始坐标（可选）：省略时取生产节点右侧 producerX+宽度+160、Y 对齐生产节点", required = false)
            @McpToolParam(description = "槽位起始坐标（可选）：省略时取生产节点右侧 producerX+宽度+160、Y 对齐生产节点", required = false)
            Position position,
            @ToolParam(description = "槽位纵向间距 px（可选）：默认 = 槽位高度 + 40", required = false)
            @McpToolParam(description = "槽位纵向间距 px（可选）：默认 = 槽位高度 + 40", required = false)
            Double gapY) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "kind", kind);
            put(out, "count", count);
            put(out, "position", position == null ? null : position.toMap());
            put(out, "gapY", gapY);
            return out;
        }
    }

    /**
     * 内联素材（可选，仅 apply_plan 的节点上用）：把一份已有素材直接折进本次整图提交，
     * 省掉单独调用 {@code canvas_graph_import_media_batch}。
     *
     * <p>语义与批量导入的 media item 完全一致：服务端按素材类型落成 image 或 video 节点，
     * 节点带 {@code ref} 后即可被其它节点的 {@code refs} 引用；同一份素材在同一个 plan 里
     * 只建一个节点，重复出现时后续节点复用同一个素材节点。
     */
    public record InlineMediaSpec(
            @ToolParam(description = "项目库素材 id（与 url 二选一，推荐）：来自本轮附件或项目库；同一 mediaId 在同一个 plan 里只建一个节点", required = false)
            @McpToolParam(description = "项目库素材 id（与 url 二选一，推荐）：来自本轮附件或项目库；同一 mediaId 在同一个 plan 里只建一个节点", required = false)
            String mediaId,
            @ToolParam(description = "素材地址（与 mediaId 二选一）：相对路径 /api/files/** 或 /api/library/media/**，不要传带 token 的绝对地址；两个都给会报错", required = false)
            @McpToolParam(description = "素材地址（与 mediaId 二选一）：相对路径 /api/files/** 或 /api/library/media/**，不要传带 token 的绝对地址；两个都给会报错", required = false)
            String url,
            @ToolParam(description = "文件名（建议填）：用于推断 MIME 与素材节点默认显示名", required = false)
            @McpToolParam(description = "文件名（建议填）：用于推断 MIME 与素材节点默认显示名", required = false)
            String name,
            @ToolParam(description = "MIME 类型（可选）：如 image/png、video/mp4；省略时按文件名/地址推断 image 或 video", required = false)
            @McpToolParam(description = "MIME 类型（可选）：如 image/png、video/mp4；省略时按文件名/地址推断 image 或 video", required = false)
            String mimeType,
            @ToolParam(description = "素材节点显示名（可选）：写入 data.label；省略时用 name 或素材地址", required = false)
            @McpToolParam(description = "素材节点显示名（可选）：写入 data.label；省略时用 name 或素材地址", required = false)
            String label) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "mediaId", mediaId);
            put(out, "url", url);
            put(out, "name", name);
            put(out, "mimeType", mimeType);
            put(out, "label", label);
            return out;
        }
    }

    /** 画布节点对象。 */
    public record NodeSpec(
            @ToolParam(description = "节点 id（可选）：省略时服务端生成 <type>-<uuid>；与 ref 同时给时以 id 为准", required = false)
            @McpToolParam(description = "节点 id（可选）：省略时服务端生成 <type>-<uuid>；与 ref 同时给时以 id 为准", required = false)
            String id,
            @ToolParam(description = "本批次内的临时引用名（可选，仅 apply_plan 用）：其他地方用 refs 引用它，如 \"g1\"", required = false)
            @McpToolParam(description = "本批次内的临时引用名（可选，仅 apply_plan 用）：其他地方用 refs 引用它，如 \"g1\"", required = false)
            String ref,
            @ToolParam(description = "节点类型（与 kind 二选一、type 优先；带 media 的素材节点不需要填，素材类型自动决定 image/video）：取值见 canvas_graph_list_node_types，生成节点只有 imageGeneration / videoGeneration", required = false)
            @McpToolParam(description = "节点类型（与 kind 二选一、type 优先；带 media 的素材节点不需要填，素材类型自动决定 image/video）：取值见 canvas_graph_list_node_types，生成节点只有 imageGeneration / videoGeneration", required = false)
            String type,
            @ToolParam(description = "节点类型的别名写法（可选）：等价于 type，取值同上", required = false)
            @McpToolParam(description = "节点类型的别名写法（可选）：等价于 type，取值同上", required = false)
            String kind,
            @ToolParam(description = "显示名（可选，写入 data.label）：如 \"场景01\"；省略时用节点类型的默认标题", required = false)
            @McpToolParam(description = "显示名（可选，写入 data.label）：如 \"场景01\"；省略时用节点类型的默认标题", required = false)
            String label,
            @ToolParam(description = "生成模式（可选，写入 data.mode）：图片 text2image|fullReference|image2image|batchRefine；视频 text2video|fullReference|firstLastFrame|videoReference", required = false)
            @McpToolParam(description = "生成模式（可选，写入 data.mode）：图片 text2image|fullReference|image2image|batchRefine；视频 text2video|fullReference|firstLastFrame|videoReference", required = false)
            String mode,
            @ToolParam(description = "提示词（可选，写入 data.prompt）", required = false)
            @McpToolParam(description = "提示词（可选，写入 data.prompt）", required = false)
            String prompt,
            @ToolParam(description = "状态（可选，写入 data.status）：默认 idle；有素材的节点用 success", required = false)
            @McpToolParam(description = "状态（可选，写入 data.status）：默认 idle；有素材的节点用 success", required = false)
            String status,
            @ToolParam(description = "坐标（可选）：省略时服务端按 3 列网格兜底（x=300+列*480，y=200+行*360），不会与已有坐标完全重合", required = false)
            @McpToolParam(description = "坐标（可选）：省略时服务端按 3 列网格兜底（x=300+列*480，y=200+行*360），不会与已有坐标完全重合", required = false)
            Position position,
            @ToolParam(description = "节点宽度 px（可选）：省略用节点类型默认宽度", required = false)
            @McpToolParam(description = "节点宽度 px（可选）：省略用节点类型默认宽度", required = false)
            Double width,
            @ToolParam(description = "节点高度 px（可选）：省略用节点类型默认高度", required = false)
            @McpToolParam(description = "节点高度 px（可选）：省略用节点类型默认高度", required = false)
            Double height,
            @ToolParam(description = "声明式上游引用（可选，仅 apply_plan 用）：本节点的输入来源，按数组顺序占用有序端口。写法 \"<ref>\" 或 \"<ref>.result.<n>\"；端口由服务端按端口表自动选择。也可引用本 plan 里带 media 的素材节点 ref", required = false)
            @McpToolParam(description = "声明式上游引用（可选，仅 apply_plan 用）：本节点的输入来源，按数组顺序占用有序端口。写法 \"<ref>\" 或 \"<ref>.result.<n>\"；端口由服务端按端口表自动选择。也可引用本 plan 里带 media 的素材节点 ref", required = false)
            List<String> refs,
            @ToolParam(description = "内联素材（可选，仅 apply_plan 用）：把已有素材直接落成图片/视频节点，省掉单独调用 canvas_graph_import_media_batch；mediaId 与 url 二选一。素材节点不是生产节点，不能同时声明 resultSlots", required = false)
            @McpToolParam(description = "内联素材（可选，仅 apply_plan 用）：把已有素材直接落成图片/视频节点，省掉单独调用 canvas_graph_import_media_batch；mediaId 与 url 二选一。素材节点不是生产节点，不能同时声明 resultSlots", required = false)
            InlineMediaSpec media,
            @ToolParam(description = "结果槽声明（可选）：只对能输出图片/视频的节点有效，一次命令内建槽并连回本节点；带 media 的素材节点不能声明", required = false)
            @McpToolParam(description = "结果槽声明（可选）：只对能输出图片/视频的节点有效，一次命令内建槽并连回本节点；带 media 的素材节点不能声明", required = false)
            ResultSlotSpec resultSlots,
            @ToolParam(description = "其余 data 扩展字段（可选，开放对象）：如 accent、assetId、assetUrl、mimeType、producerNodeId；上面的扁平字段会覆盖同名键", required = false)
            @McpToolParam(description = "其余 data 扩展字段（可选，开放对象）：如 accent、assetId、assetUrl、mimeType、producerNodeId；上面的扁平字段会覆盖同名键", required = false)
            Map<String, Object> data) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "id", id);
            put(out, "ref", ref);
            put(out, "type", type);
            put(out, "kind", kind);
            put(out, "position", position == null ? null : position.toMap());
            put(out, "width", width);
            put(out, "height", height);
            put(out, "refs", refs);
            put(out, "media", media == null ? null : media.toMap());
            put(out, "resultSlots", resultSlots == null ? null : resultSlots.toMap());
            Map<String, Object> merged = new LinkedHashMap<>();
            putAll(merged, data);
            put(merged, "label", label);
            put(merged, "mode", mode);
            put(merged, "prompt", prompt);
            put(merged, "status", status);
            if (!merged.isEmpty()) out.put("data", merged);
            return out;
        }
    }

    /** 画布连线对象。 */
    public record EdgeSpec(
            @ToolParam(description = "连线 id（可选）：省略时服务端生成 edge-<uuid>", required = false)
            @McpToolParam(description = "连线 id（可选）：省略时服务端生成 edge-<uuid>", required = false)
            String id,
            @ToolParam(description = "来源节点 id（必填）", required = true)
            @McpToolParam(description = "来源节点 id（必填）", required = true) String source,
            @ToolParam(description = "来源输出端口 id（可选）：省略时取来源节点的第一个输出端口；生成节点是 image 或 video", required = false)
            @McpToolParam(description = "来源输出端口 id（可选）：省略时取来源节点的第一个输出端口；生成节点是 image 或 video", required = false)
            String sourceHandle,
            @ToolParam(description = "目标节点 id（必填）", required = true)
            @McpToolParam(description = "目标节点 id（必填）", required = true) String target,
            @ToolParam(description = "目标输入端口 id（可选）：省略时取第一个能接受来源类型的输入端口；常用 prompt / reference / origin / clips / video", required = false)
            @McpToolParam(description = "目标输入端口 id（可选）：省略时取第一个能接受来源类型的输入端口；常用 prompt / reference / origin / clips / video", required = false)
            String targetHandle,
            @ToolParam(description = "有序端口(reference/first/last/clips)的位次（可选，从 0 起）：省略时自动取该端口下一个空位；重复占用会报 PORT_ORDER_CONFLICT", required = false)
            @McpToolParam(description = "有序端口(reference/first/last/clips)的位次（可选，从 0 起）：省略时自动取该端口下一个空位；重复占用会报 PORT_ORDER_CONFLICT", required = false)
            Integer portOrder,
            @ToolParam(description = "是否标记为结果槽连线（可选，默认 false）", required = false)
            @McpToolParam(description = "是否标记为结果槽连线（可选，默认 false）", required = false) Boolean resultSlot,
            @ToolParam(description = "结果槽顺序（可选，从 0 起）：与 resultSlot=true 搭配", required = false)
            @McpToolParam(description = "结果槽顺序（可选，从 0 起）：与 resultSlot=true 搭配", required = false)
            Integer resultSlotOrder,
            @ToolParam(description = "其余连线 data 扩展字段（可选，开放对象）：如 kind", required = false)
            @McpToolParam(description = "其余连线 data 扩展字段（可选，开放对象）：如 kind", required = false)
            Map<String, Object> data) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "id", id);
            put(out, "source", source);
            put(out, "sourceHandle", sourceHandle);
            put(out, "target", target);
            put(out, "targetHandle", targetHandle);
            put(out, "portOrder", portOrder);
            Map<String, Object> merged = new LinkedHashMap<>();
            putAll(merged, data);
            put(merged, "portOrder", portOrder);
            put(merged, "resultSlot", resultSlot);
            put(merged, "resultSlotOrder", resultSlotOrder);
            if (!merged.isEmpty()) out.put("data", merged);
            return out;
        }
    }

    /** 媒体导入对象：把已有素材（项目库 mediaId / 画布 assetId / 可下载 url）落成画布图片或视频节点。 */
    public record MediaSpec(
            @ToolParam(description = "媒体类型：image 或 video；省略时按 mimeType/文件名推断", required = false)
            @McpToolParam(description = "媒体类型：image 或 video；省略时按 mimeType/文件名推断", required = false)
            String kind,
            @ToolParam(description = "素材地址（四选一）：相对路径 /api/files/** 或 /api/library/media/**；项目库素材优先用 mediaId，不要传带 token 的签名 URL", required = false)
            @McpToolParam(description = "素材地址（四选一）：相对路径 /api/files/** 或 /api/library/media/**；项目库素材优先用 mediaId，不要传带 token 的签名 URL", required = false)
            String url,
            @ToolParam(description = "画布内已有资产 id（四选一）：必须是本画布 canvas_assets.id", required = false)
            @McpToolParam(description = "画布内已有资产 id（四选一）：必须是本画布 canvas_assets.id", required = false)
            String assetId,
            @ToolParam(description = "项目库素材 id（四选一，推荐）：来自本轮附件或项目库", required = false)
            @McpToolParam(description = "项目库素材 id（四选一，推荐）：来自本轮附件或项目库", required = false)
            String mediaId,
            @ToolParam(description = "项目库资产库 id（四选一）：asset.library_* 的统一资产 id", required = false)
            @McpToolParam(description = "项目库资产库 id（四选一）：asset.library_* 的统一资产 id", required = false)
            String libraryAssetId,
            @ToolParam(description = "文件名（可选）：用于推断 MIME 与默认 label", required = false)
            @McpToolParam(description = "文件名（可选）：用于推断 MIME 与默认 label", required = false)
            String name,
            @ToolParam(description = "MIME 类型（可选）：如 image/png、video/mp4", required = false)
            @McpToolParam(description = "MIME 类型（可选）：如 image/png、video/mp4", required = false)
            String mimeType,
            @ToolParam(description = "新节点 id（可选）：省略时服务端生成 media-<uuid>", required = false)
            @McpToolParam(description = "新节点 id（可选）：省略时服务端生成 media-<uuid>", required = false)
            String nodeId,
            @ToolParam(description = "节点显示名（可选）：省略时用 name 或素材地址", required = false)
            @McpToolParam(description = "节点显示名（可选）：省略时用 name 或素材地址", required = false)
            String label,
            @ToolParam(description = "节点坐标（可选）：省略时走网格兜底", required = false)
            @McpToolParam(description = "节点坐标（可选）：省略时走网格兜底", required = false)
            Position position,
            @ToolParam(description = "节点宽度 px（可选）", required = false)
            @McpToolParam(description = "节点宽度 px（可选）", required = false) Double width,
            @ToolParam(description = "节点高度 px（可选）", required = false)
            @McpToolParam(description = "节点高度 px（可选）", required = false) Double height,
            @ToolParam(description = "结果槽声明（可选）：图片媒体默认 kind=image、视频媒体默认 kind=video", required = false)
            @McpToolParam(description = "结果槽声明（可选）：图片媒体默认 kind=image、视频媒体默认 kind=video", required = false)
            ResultSlotSpec resultSlots,
            @ToolParam(description = "其余 data 扩展字段（可选，开放对象）", required = false)
            @McpToolParam(description = "其余 data 扩展字段（可选，开放对象）", required = false)
            Map<String, Object> data) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "kind", kind);
            put(out, "url", url);
            put(out, "assetId", assetId);
            put(out, "mediaId", mediaId);
            put(out, "libraryAssetId", libraryAssetId);
            put(out, "name", name);
            put(out, "mimeType", mimeType);
            put(out, "nodeId", nodeId);
            put(out, "label", label);
            put(out, "position", position == null ? null : position.toMap());
            put(out, "width", width);
            put(out, "height", height);
            put(out, "resultSlots", resultSlots == null ? null : resultSlots.toMap());
            put(out, "data", data);
            return out;
        }
    }

    /** 布局参数。 */
    public record LayoutSpec(
            @ToolParam(description = "布局模式：grid（默认，按 sqrt(n) 列铺开）| horizontal（一行）| vertical（一列）| dag（按连线依赖分层，链路图推荐）", required = false)
            @McpToolParam(description = "布局模式：grid（默认，按 sqrt(n) 列铺开）| horizontal（一行）| vertical（一列）| dag（按连线依赖分层，链路图推荐）", required = false)
            String mode,
            @ToolParam(description = "水平间距 px（可选，默认 520）", required = false)
            @McpToolParam(description = "水平间距 px（可选，默认 520）", required = false) Double gapX,
            @ToolParam(description = "垂直间距 px（可选，默认 420）", required = false)
            @McpToolParam(description = "垂直间距 px（可选，默认 420）", required = false) Double gapY,
            @ToolParam(description = "每行列数（可选，仅 grid 模式）：省略时按 sqrt(n) 计算", required = false)
            @McpToolParam(description = "每行列数（可选，仅 grid 模式）：省略时按 sqrt(n) 计算", required = false)
            Integer columns,
            @ToolParam(description = "起点坐标（可选）：省略时取参与布局节点里的最小 x/y", required = false)
            @McpToolParam(description = "锚点坐标（可选）：省略时取参与布局节点里的最小 x/y", required = false)
            Position anchor,
            @ToolParam(description = "参与布局的节点 id（可选）：省略时排布整张画布", required = false)
            @McpToolParam(description = "参与布局的节点 id（可选）：省略时排布整张画布", required = false)
            List<String> nodeIds) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "mode", mode);
            put(out, "gapX", gapX);
            put(out, "gapY", gapY);
            put(out, "columns", columns);
            put(out, "anchor", anchor == null ? null : anchor.toMap());
            put(out, "nodeIds", nodeIds);
            return out;
        }
    }

    /** create_result_slots 的参数。 */
    public record ResultSlotArgs(
            @ToolParam(description = "生产节点 id（必填）：必须是有 image/video 输出的节点", required = true)
            @McpToolParam(description = "生产节点 id（必填）：必须是有 image/video 输出的节点", required = true)
            String producerNodeId,
            @ToolParam(description = "槽位媒体类型（必填）：image 或 video，必须与该生产节点的输出一致", required = true)
            @McpToolParam(description = "槽位媒体类型（必填）：image 或 video，必须与该生产节点的输出一致", required = true)
            String kind,
            @ToolParam(description = "槽位数量（必填）：1~12", required = true)
            @McpToolParam(description = "槽位数量（必填）：1~12", required = true) Integer count,
            @ToolParam(description = "槽位起始坐标（可选）：省略时取生产节点右侧 producerX+宽度+160", required = false)
            @McpToolParam(description = "槽位起始坐标（可选）：省略时取生产节点右侧 producerX+宽度+160", required = false)
            Position position,
            @ToolParam(description = "槽位纵向间距 px（可选）：默认 = 槽位高度 + 40", required = false)
            @McpToolParam(description = "槽位纵向间距 px（可选）：默认 = 槽位高度 + 40", required = false)
            Double gapY) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "producerNodeId", producerNodeId);
            put(out, "kind", kind);
            put(out, "count", count);
            put(out, "position", position == null ? null : position.toMap());
            put(out, "gapY", gapY);
            return out;
        }
    }

    /** mark_result_slots 的参数。 */
    public record MarkResultSlotArgs(
            @ToolParam(description = "生产节点 id（必填）", required = true)
            @McpToolParam(description = "生产节点 id（必填）", required = true) String producerNodeId,
            @ToolParam(description = "要标记为结果槽的现有空图片/视频节点 id 列表（必填，按顺序即槽位顺序）", required = true)
            @McpToolParam(description = "要标记为结果槽的现有空图片/视频节点 id 列表（必填，按顺序即槽位顺序）", required = true)
            List<String> nodeIds,
            @ToolParam(description = "起始顺序（可选，默认 0）", required = false)
            @McpToolParam(description = "起始顺序（可选，默认 0）", required = false) Integer startOrder) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "producerNodeId", producerNodeId);
            put(out, "nodeIds", nodeIds);
            put(out, "startOrder", startOrder);
            return out;
        }
    }

    /**
     * update_node 的 data 补丁。
     *
     * <p>两种写法等价：① 直接传 data 字段名（本对象的扁平字段，推荐）；② 传 {@code data:{...}} 对象。
     * 只允许改 data，{@code id/type/kind} 不可改。没传的字段不会被置空。
     */
    public record NodePatch(
            @ToolParam(description = "新显示名（可选）：等价于 data.label", required = false)
            @McpToolParam(description = "新显示名（可选）：等价于 data.label", required = false) String label,
            @ToolParam(description = "生成模式（可选）：等价于 data.mode", required = false)
            @McpToolParam(description = "生成模式（可选）：等价于 data.mode", required = false) String mode,
            @ToolParam(description = "提示词（可选）：等价于 data.prompt", required = false)
            @McpToolParam(description = "提示词（可选）：等价于 data.prompt", required = false) String prompt,
            @ToolParam(description = "状态（可选）：等价于 data.status", required = false)
            @McpToolParam(description = "状态（可选）：等价于 data.status", required = false) String status,
            @ToolParam(description = "绑定的素材 id（可选）：等价于 data.assetId", required = false)
            @McpToolParam(description = "绑定的素材 id（可选）：等价于 data.assetId", required = false) String assetId,
            @ToolParam(description = "素材地址（可选）：等价于 data.assetUrl", required = false)
            @McpToolParam(description = "素材地址（可选）：等价于 data.assetUrl", required = false) String assetUrl,
            @ToolParam(description = "其余 data 字段（可选，开放对象）：与上面的扁平字段等价，同名时以扁平字段为准", required = false)
            @McpToolParam(description = "其余 data 字段（可选，开放对象）：与上面的扁平字段等价，同名时以扁平字段为准", required = false)
            Map<String, Object> data) {

        /** 扁平字段优先，并且只写入非 null 值（避免把没传的字段置空）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> merged = new LinkedHashMap<>();
            putAll(merged, data);
            put(merged, "label", label);
            put(merged, "mode", mode);
            put(merged, "prompt", prompt);
            put(merged, "status", status);
            put(merged, "assetId", assetId);
            put(merged, "assetUrl", assetUrl);
            return merged;
        }
    }

    /**
     * apply_plan.updates 的单项：改一个**已有**节点的 data 字段。
     *
     * <p>与 update_node 同一语义（{@code id/type/kind} 不可改、没传的字段不置空），
     * 区别只是它和建节点、建连线、标记结果槽共用同一次提交、同一个 revision。
     */
    public record NodeUpdateSpec(
            @ToolParam(description = "要修改的已有节点 id（必填）", required = true)
            @McpToolParam(description = "要修改的已有节点 id（必填）", required = true) String nodeId,
            @ToolParam(description = "要写入的 data 字段（必填）：扁平字段与 data={...} 等价；id/type/kind 不可改", required = true)
            @McpToolParam(description = "要写入的 data 字段（必填）：扁平字段与 data={...} 等价；id/type/kind 不可改", required = true)
            NodePatch patch) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "nodeId", nodeId);
            put(out, "patch", patch == null ? null : patch.toMap());
            return out;
        }
    }

    /** apply_plan 的计划体。 */
    public record PlanSpec(
            @ToolParam(description = "要创建的节点列表（可选，1~100）；每个节点可用 ref 声明临时引用名，供其他节点的 refs 引用；"
                    + "节点可带 media 内联素材，把「导入素材 + 建图」压成一次调用。纯改图时可以不传（与 updates / markResultSlots 至少给一个）",
                    required = false)
            @McpToolParam(description = "要创建的节点列表（可选，1~100）；每个节点可用 ref 声明临时引用名，供其他节点的 refs 引用；"
                    + "节点可带 media 内联素材，把「导入素材 + 建图」压成一次调用。纯改图时可以不传（与 updates / markResultSlots 至少给一个）",
                    required = false)
            List<NodeSpec> nodes,
            @ToolParam(description = "显式连线（可选）：与 refs 可并存；完全相同的已有连线会被幂等忽略、不报错", required = false)
            @McpToolParam(description = "显式连线（可选）：与 refs 可并存；完全相同的已有连线会被幂等忽略、不报错", required = false)
            List<EdgeSpec> edges,
            @ToolParam(description = "改已有节点的 data 字段（可选）：每项 {nodeId, patch}；与建节点/连线/标记槽共用同一个 revision", required = false)
            @McpToolParam(description = "改已有节点的 data 字段（可选）：每项 {nodeId, patch}；与建节点/连线/标记槽共用同一个 revision", required = false)
            List<NodeUpdateSpec> updates,
            @ToolParam(description = "把已有空图片/视频节点标记为某生产节点的结果槽（可选）：每项 {producerNodeId, nodeIds, startOrder?}；"
                    + "不新建节点，只补连线与槽位顺序", required = false)
            @McpToolParam(description = "把已有空图片/视频节点标记为某生产节点的结果槽（可选）：每项 {producerNodeId, nodeIds, startOrder?}；"
                    + "不新建节点，只补连线与槽位顺序", required = false)
            List<MarkResultSlotArgs> markResultSlots,
            @ToolParam(description = "收尾布局（可选）：在本批次变更之后、同一次保存内执行，不会额外产生 revision；不传布局参数时默认排布本批次节点", required = false)
            @McpToolParam(description = "收尾布局（可选）：在本批次变更之后、同一次保存内执行，不会额外产生 revision；不传布局参数时默认排布本批次节点", required = false)
            LayoutSpec layout,
            @ToolParam(description = "是否删掉本批次建出、且没有任何下游连线的结果槽（可选，默认 false）", required = false)
            @McpToolParam(description = "是否删掉本批次建出、且没有任何下游连线的结果槽（可选，默认 false）", required = false)
            Boolean pruneUnreferencedResultSlots) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            put(out, "nodes", toNodeMaps(nodes));
            put(out, "edges", toEdgeMaps(edges));
            put(out, "updates", toUpdateMaps(updates));
            put(out, "markResultSlots", toMarkSlotMaps(markResultSlots));
            put(out, "layout", layout == null ? null : layout.toMap());
            put(out, "pruneUnreferencedResultSlots", pruneUnreferencedResultSlots);
            return out;
        }
    }

    static List<Map<String, Object>> toUpdateMaps(List<NodeUpdateSpec> updates) {
        if (updates == null) return null;
        return updates.stream().filter(Objects::nonNull).map(NodeUpdateSpec::toMap).toList();
    }

    static List<Map<String, Object>> toMarkSlotMaps(List<MarkResultSlotArgs> items) {
        if (items == null) return null;
        return items.stream().filter(Objects::nonNull).map(MarkResultSlotArgs::toMap).toList();
    }

    static List<Map<String, Object>> toNodeMaps(List<NodeSpec> nodes) {
        if (nodes == null) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (NodeSpec node : nodes) out.add(node == null ? Map.of() : node.toMap());
        return out;
    }

    static List<Map<String, Object>> toEdgeMaps(List<EdgeSpec> edges) {
        if (edges == null) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (EdgeSpec edge : edges) out.add(edge == null ? Map.of() : edge.toMap());
        return out;
    }

    static List<Map<String, Object>> toMediaMaps(List<MediaSpec> items) {
        if (items == null) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaSpec item : items) out.add(item == null ? Map.of() : item.toMap());
        return out;
    }
}
