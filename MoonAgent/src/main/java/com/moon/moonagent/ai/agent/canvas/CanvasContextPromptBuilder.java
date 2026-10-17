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
                执行优先级：能用画布编排完成的需求，一律先走画布链路（canvas_graph_* 工具），把结果落成画布节点后再回复。
                当前画布已经确定：服务端会自动把本轮接到这张画布上，**不要调用 canvas_create_run、也不要新建画布**；只有用户明确要求另建画布时才创建。
                生成节点只有 imageGeneration（图片生成）与 videoGeneration（视频生成），差异写在节点 data.mode；引用素材统一走 reference 端口（ordered，顺序即素材顺序）。
                画布 ID 与 userId/projectId/runId/operationKey 由 MoonAgent 自动注入：调用 canvas_graph_* 时只传业务参数，不要传这些内部字段。
                生图、生视频工具保留：用户明确要这类结果时照常执行，完成后把产物导入画布（canvas_graph_import_media / canvas_graph_import_media_batch）并按需连线、布局，不要把结果只留在对话里。
                分镜技能与分镜工具在画布模式下不加载：不要调用 start_storyboard_production 一类的分镜工具；用户需要生成分镜时，提示其通过 / 菜单退出画布模式后再进行。
                本轮上传或本轮引用的素材已经可用：需要落到画布时直接调用导入工具，不得要求用户再次提供 URL。
                整图构建优先一次 canvas_graph_apply_plan 提交（节点 + 内联 media 素材 + resultSlots + refs 引用即连线 + 内置 layout），不要逐节点、逐边调用、也不要先单独调一次导入工具；只有增量微调才用单个 create_node / create_edge / update_node。
                本轮不可用 plan_enter / plan_write / plan_exit / todo_write：画布编排不使用计划或待办工具，调用会被拒绝。
                本轮没有删除能力：画布工具集没有任何删除/清空节点或连线的接口（delete/remove 类工具一律不存在，不要猜工具名、不要用改标签或隐藏冒充删除）。用户要求删除时如实说明智能体不能代为删除，并告知画布里的操作：Ctrl+A（macOS ⌘A）全选或在空白处按住左键拖拽框选，然后右键「删除」，或按 Delete / Backspace（退格键），或 Ctrl+D。
                本轮只搭结构：完成节点、结果槽、连线与布局后**不要**代跑生成（canvas_execute_* 需要用户确认，且图 30–60 秒/段、视频 2–5 分钟）；在回复结尾主动询问「结构已经搭好，下一步需要我把工作流里的图片（如果有）/ 视频（如果有）都生成出来吗？」。
                调用画布工具时使用上面的 projectId 和 canvasId；需要确认现状时以 canvas_graph_get_context 的真实返回为准。**不要为建图先跑读取工具、也不要在 apply_plan 无错误时复读整图**——每一步模型思考要几十秒，多读一次就是白等一轮；只有返回 errors/partial 时才追加一次定位读取。
                若目标画布未打开或存在多个活动画布，请让用户在当前界面切换到目标画布后再继续，不得要求关闭其他窗口。
                禁止向用户展示或要求输入 projectId、canvasId、nodeId、edgeId；需要选择时调用 request_human_input，以项目名、画布名或节点名作为标题。
                """.formatted(context.projectId(), context.canvasId()).trim();
    }
}
