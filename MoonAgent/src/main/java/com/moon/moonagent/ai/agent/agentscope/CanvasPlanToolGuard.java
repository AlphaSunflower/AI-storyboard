package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画布轮里禁用计划/待办工具的每轮作用域守卫。
 *
 * <p>背景：{@code plan_enter} / {@code plan_write} / {@code plan_exit}（PlanModeTools）与
 * {@code todo_write}（TaskList）由 harness/core 在 <b>build 期</b>注册进 Toolkit，且**不带 group**；
 * AgentScope 的 {@code getToolSchemas(activeGroups)} 语义是「ungrouped 工具永远可见」，而 Toolkit
 * 是全局单例（build 期被 HarnessAgent 深拷贝一份后所有会话共用）。因此**不能**按轮从注册表摘除工具
 * （会踩到并发会话），只能换成这个「每轮自己判断」的包装。
 *
 * <p>失败开放（fail-open）是第一原则：拿不到 run context、非画布轮、或守卫自身判断抛异常时，
 * 一律原样透传给 delegate，绝不因为守卫出错而中断 turn。
 */
final class CanvasPlanToolGuard extends ToolBase {

    /**
     * 需要加守卫的工具名（顺序固定，便于日志与测试断言）。
     *
     * <p>这些是 AgentScope 框架自带的计划/待办工具，不是 Moon 自研工具，因此不进
     * {@link ToolCatalog}；升级 AgentScope 时若框架改名，需要同步这里。
     */
    static final List<String> GUARDED_TOOL_NAMES =
            List.of("plan_enter", "plan_write", "plan_exit", "todo_write");

    /** 画布轮的纠偏文案：直接给一次调用就能做完整张图的正解。 */
    static final String CANVAS_MODE_MESSAGE =
            "画布编排不使用计划/待办工具；请直接调用 canvas_graph_apply_plan "
                    + "在一次调用里提交整张图（节点/结果槽/连线/布局）。";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentTool delegate;
    private final AgentCanvasRunService runs;

    CanvasPlanToolGuard(AgentTool delegate, AgentCanvasRunService runs) {
        super(ToolBase.builder()
                .name(delegate.getName())
                .description(delegate.getDescription())
                .inputSchema(delegate.getParameters() == null
                        ? Map.of("type", "object", "properties", Map.of())
                        : delegate.getParameters())
                .readOnly(delegate.isReadOnly())
                .concurrencySafe(delegate instanceof ToolBase safe && safe.isConcurrencySafe())
                .externalTool(delegate instanceof ToolBase ext && ext.isExternalTool())
                .stateInjected(delegate instanceof ToolBase state && state.isStateInjected())
                .returnDirect(delegate.isReturnDirect()));
        this.delegate = delegate;
        this.runs = runs;
    }

    /** 被包装的原始工具（测试用于断言"只包一层"）。 */
    AgentTool delegate() {
        return delegate;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            if (isCanvasTurn(param)) {
                return Mono.just(rejection(param));
            }
        } catch (RuntimeException ignored) {
            // fail-open：守卫自身判断出错时绝不阻断工具调用
        }
        return delegate.callAsync(param);
    }

    /** 本轮是否为画布轮：画布编辑上下文，或本轮已自动接管了一条 canvas run。 */
    private boolean isCanvasTurn(ToolCallParam param) {
        AgentScopeRunContext context = context(param);
        if (context == null) return false;
        OrchestrationRequest request = context.request();
        if (request != null) {
            CanvasSessionContext canvas = request.getCanvasContext();
            if (canvas != null && canvas.isCanvasEditMode()) return true;
            String turnId = request.getTurnId();
            if (turnId != null && !turnId.isBlank() && runs != null
                    && runs.activeByTurn(turnId) != null) {
                return true;
            }
        }
        return false;
    }

    private ToolResultBlock rejection(ToolCallParam param) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("code", "PLAN_TOOLS_DISABLED_IN_CANVAS_MODE");
        payload.put("tool", getName());
        payload.put("message", CANVAS_MODE_MESSAGE);
        String text;
        try {
            text = JSON.writeValueAsString(payload);
        } catch (Exception ignored) {
            text = "{\"ok\":false,\"code\":\"PLAN_TOOLS_DISABLED_IN_CANVAS_MODE\"}";
        }
        String toolCallId = param == null || param.getToolUseBlock() == null
                ? "" : param.getToolUseBlock().getId();
        String toolName = param == null || param.getToolUseBlock() == null
                ? getName() : param.getToolUseBlock().getName();
        return ToolResultBlock.of(
                toolCallId,
                toolName,
                TextBlock.builder().text(text).build());
    }

    private static AgentScopeRunContext context(ToolCallParam param) {
        return param == null || param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }
}
