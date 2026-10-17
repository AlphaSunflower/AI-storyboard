package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 画布「结构性步骤」用最小思考，创意步骤保留完整思考。
 *
 * <p><b>为什么</b>：实测一轮建图约 14.5 分钟，其中**画布命令总耗时不到 2 秒**，其余全花在
 * 「两次工具调用之间」的模型思考上（单步 23–272 秒）。而画布编排里的多数步骤只是"把上一步的
 * 工具结果接着组装成下一次调用"（结构性续跑），并不需要长思考；真正需要思考的是首轮创意定调
 * （拆镜头、写提示词、定风格）。
 *
 * <p><b>触发条件（两个同时满足才压）</b>：
 * <ol>
 *   <li>本轮是画布编辑模式（{@link OrchestrationRequest#getCanvasContext()} 且
 *       {@link CanvasSessionContext#isCanvasEditMode()}）；</li>
 *   <li>本步输入的最后一条消息是 {@code canvas_*} 工具的结果——说明模型正在续跑，
 *       而不是刚收到用户需求。</li>
 * </ol>
 * 因此「用户消息 → 首次规划」这一步**保持完整思考**，只有后续的结构性续跑才压。
 *
 * <p><b>怎么压</b>：通过 {@link GenerateOptions#mergeOptions} 生成一份"字段全保留、仅追加
 * {@code thinking_level=minimal}"的选项副本（与仓库里标题服务用 extraBody 压思考同一口径）。
 *
 * <p><b>fail-open</b>：拿不到 run context、判据抛异常、选项为空、已经是最小思考 —— 一律原样
 * 透传。这个类只可能让模型更快，绝不允许因为它而中断或改变一次模型调用的其它语义。
 */
@Slf4j
final class CanvasStructuralThinkingMiddleware implements MiddlewareBase {

    /** 与标题服务保持一致的口径：网关认这个顶层参数。 */
    static final String THINKING_LEVEL_KEY = "thinking_level";
    static final String THINKING_LEVEL_MINIMAL = "minimal";
    /** 画布工具前缀：只有"上一步刚跑完画布工具"才判定为结构性续跑。 */
    static final String CANVAS_TOOL_PREFIX = "canvas";

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent,
                                        RuntimeContext ctx,
                                        ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        try {
            GenerateOptions reduced = reducedOptions(ctx, input);
            if (reduced == null) {
                return next.apply(input);
            }
            log.debug("[agent-speed] 结构性步骤压缩思考 turn={} 上一步工具={}",
                    turnId(ctx), lastMessageName(input.messages()));
            return next.apply(new ModelCallInput(input.messages(), input.tools(), reduced, input.model()));
        } catch (RuntimeException error) {
            // 优化失败绝不能影响正常调用
            log.warn("[agent-speed] 压缩思考失败，按完整思考继续: {}", error.getMessage());
            return next.apply(input);
        }
    }

    /** 需要压缩时返回替换用的选项；不需要时返回 {@code null}（调用方原样透传）。 */
    private static GenerateOptions reducedOptions(RuntimeContext ctx, ModelCallInput input) {
        if (!isCanvasTurn(ctx) || !followsCanvasTool(input.messages())) return null;
        GenerateOptions options = input.options();
        if (options == null) return null;
        Map<String, Object> body = options.getAdditionalBodyParams();
        if (body != null && THINKING_LEVEL_MINIMAL.equals(body.get(THINKING_LEVEL_KEY))) {
            return null; // 已经是最小思考，不重复包装
        }
        GenerateOptions override = GenerateOptions.builder()
                .additionalBodyParam(THINKING_LEVEL_KEY, THINKING_LEVEL_MINIMAL)
                .build();
        // primary 覆盖 fallback：override 只带 thinking_level，其余字段全部沿用原选项
        return GenerateOptions.mergeOptions(override, options);
    }

    /** 本轮是否为画布编辑模式；拿不到上下文一律返回 false（不压）。 */
    private static boolean isCanvasTurn(RuntimeContext ctx) {
        if (ctx == null) return false;
        AgentScopeRunContext context = ctx.get(AgentScopeRunContext.class);
        OrchestrationRequest request = context == null ? null : context.request();
        CanvasSessionContext canvas = request == null ? null : request.getCanvasContext();
        return canvas != null && canvas.isCanvasEditMode();
    }

    /** 本步输入的最后一条消息是否是画布工具的执行结果。 */
    private static boolean followsCanvasTool(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return false;
        Msg last = messages.get(messages.size() - 1);
        if (last == null || last.getRole() != MsgRole.TOOL) return false;
        String name = last.getName();
        return name != null && name.startsWith(CANVAS_TOOL_PREFIX);
    }

    private static String lastMessageName(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return "";
        Msg last = messages.get(messages.size() - 1);
        return last == null || last.getName() == null ? "" : last.getName();
    }

    private static String turnId(RuntimeContext ctx) {
        AgentScopeRunContext context = ctx == null ? null : ctx.get(AgentScopeRunContext.class);
        OrchestrationRequest request = context == null ? null : context.request();
        String turnId = request == null ? null : request.getTurnId();
        return turnId == null ? "" : turnId;
    }
}
