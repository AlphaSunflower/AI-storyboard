package com.moon.moonagent.ai.agent.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.function.Consumer;

/**
 * 在画布工具实际执行前发送人类可读的工作流进度，避免长时间工具循环看起来卡住。
 */
public final class CanvasToolProgressCallback implements ToolCallback {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolCallback delegate;
    private final Consumer<String> progress;

    public CanvasToolProgressCallback(ToolCallback delegate, Consumer<String> progress) {
        this.delegate = delegate;
        this.progress = progress;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        emit(toolInput);
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        emit(toolInput);
        return delegate.call(toolInput, toolContext);
    }

    private void emit(String toolInput) {
        String name = getToolDefinition().name();
        JsonNode args = parse(toolInput);
        String message = switch (name) {
            case "canvas_import_media" -> "正在导入" + kindLabel(args.path("kind").asText(""));
            case "canvas_create_result_slots" -> "正在创建 " + args.path("count").asInt(1)
                    + " 个" + kindLabel(args.path("kind").asText("")) + "结果槽";
            case "canvas_mark_result_slots" -> "正在标记 " + args.path("nodeIds").size() + " 个结果槽";
            case "canvas_create_node", "canvas_create_nodes" -> "正在创建画布节点";
            case "canvas_update_node" -> "正在更新节点";
            case "canvas_get_node_model_config" -> "正在读取节点模型参数";
            case "canvas_set_node_model_config" -> "正在调整节点模型参数";
            case "canvas_delete_nodes" -> "正在删除节点";
            case "canvas_create_edge" -> "正在连接节点";
            case "canvas_delete_edges" -> "正在删除连线";
            case "canvas_layout_graph" -> "正在整理画布布局";
            case "canvas_move_nodes" -> "正在移动节点";
            case "canvas_resize_nodes" -> "正在调整节点尺寸";
            case "canvas_pin_nodes" -> "正在固定节点";
            case "canvas_execute_node" -> "正在提交节点执行";
            case "canvas_execute_graph" -> "正在启动整张画布执行";
            case "canvas_get_execution" -> "正在读取执行进度";
            case "canvas_cancel_execution" -> "正在取消画布执行";
            case "canvas_image_crop" -> "正在裁剪图片";
            case "canvas_image_split" -> "正在切分图片";
            case "canvas_image_adjust" -> "正在调整图片效果";
            case "canvas_undo" -> "正在撤销画布操作";
            case "canvas_redo" -> "正在重做画布操作";
            case "canvas_get_context", "canvas_get_browser_context" -> "正在读取画布上下文";
            case "canvas_get_state", "canvas_get_summary", "canvas_get_node",
                    "canvas_list_nodes", "canvas_list_edges", "canvas_get_selection",
                    "canvas_list_node_types" -> "正在读取画布状态";
            default -> "正在执行画布操作";
        };
        progress.accept(message);
    }

    private static JsonNode parse(String input) {
        try {
            return input == null || input.isBlank()
                    ? OBJECT_MAPPER.createObjectNode()
                    : OBJECT_MAPPER.readTree(input);
        } catch (Exception ignored) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "image" -> "图片";
            case "video" -> "视频";
            case "audio" -> "音频";
            default -> "素材";
        };
    }
}
