package com.moon.moonagent.ai.agent.canvas;

import com.moon.moonagent.ai.agent.CanvasSessionContext;

/**
 * 把画布范围与执行优先级写入每轮系统提示：
 * 能用画布编排完成的需求先走画布链路，保留生图与生视频能力，产物默认落回画布；
 * 分镜技能与分镜工具在画布模式下不加载。
 */
public final class CanvasContextPromptBuilder {

    private CanvasContextPromptBuilder() {
    }

    public static String build(CanvasSessionContext context) {
        if (context == null || !context.isCanvasEditMode()) return "";
        return """
                ## 当前画布编辑模式
                projectId=%s
                canvasId=%s
                执行优先级：能用画布编排完成的需求，一律先走画布链路（canvas_create_run 与 canvas_graph_* 工具），把结果落成画布节点后再回复。
                生图、生视频工具保留：用户明确要这类结果时照常执行，完成后把产物导入画布（canvas_graph_import_media / canvas_graph_import_media_batch）并按需连线、布局，不要把结果只留在对话里。
                分镜技能与分镜工具在画布模式下不加载：不要调用 start_storyboard_production 一类的分镜工具；用户需要生成分镜时，提示其通过 / 菜单退出画布模式后再进行。
                本轮上传或本轮引用的素材已经可用：需要落到画布时直接调用导入工具，不得要求用户再次提供 URL。
                调用画布工具时使用上面的 projectId 和 canvasId，每次执行前以 canvas_graph_get_context 的真实返回为准。
                若目标画布未打开或存在多个活动画布，请让用户在当前界面切换到目标画布后再继续，不得要求关闭其他窗口。
                禁止向用户展示或要求输入 projectId、canvasId、nodeId、edgeId；需要选择时调用 request_human_input，以项目名、画布名或节点名作为标题。
                """.formatted(context.projectId(), context.canvasId()).trim();
    }
}
