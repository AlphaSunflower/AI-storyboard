package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.creative.CreativeModelRouter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「每轮钉住、闲置才换」的默认模型选择器。
 *
 * <p>为什么需要它：AgentScope 的 {@code ReActAgent.model} 是 {@code private final}
 * （且 {@code HarnessAgent.Builder.modelResolver} 只用于子智能体的模型覆盖），运行中无法给
 * 已有实例换模型；不重建 agent 的唯一办法就是让 agent 持有的 {@link GatewayDefaultModel}
 * 包装器每次调用时解析真实模型名。而"每次调用都解析"会带来两个副作用：一次 ReAct 循环里
 * 可能跨模型、上下文窗口在轮内变化。这里用「在途 turn 计数」把它收敛成业务语义：
 *
 * <ul>
 *   <li><b>有在途 turn</b>（用户正在发送/智能体正在跑）→ 沿用本轮开始时钉住的模型，绝不中途换；</li>
 *   <li><b>无在途 turn</b>（智能体闲置）→ 下一轮开始时重新解析网关默认模型，切换立即被捡起。</li>
 * </ul>
 *
 * <p>多个会话并发时（MoonAgent 允许不同会话并行），只有当<b>全部</b> turn 都结束时才算闲置；
 * 因此并发场景下新默认从"下一次完全空闲时开始的 turn"起生效，语义保守但绝不中断在途回合。
 */
@Component
@RequiredArgsConstructor
public class TurnModelPin {

    private static final Logger log = LoggerFactory.getLogger(TurnModelPin.class);

    private final CreativeModelRouter modelRouter;
    private final AgentScopeProperties properties;

    /** 在途 turn 数：> 0 表示智能体忙，此时不刷新钉住的模型 */
    private final AtomicInteger activeTurns = new AtomicInteger();
    /** 当前钉住的模型名（本轮生效值） */
    private volatile String pinned;

    /**
     * 进入一轮：仅当这是"从闲置开始"的第一轮时才重新解析默认模型。
     *
     * @return 本次是否计入了在途计数（调用方必须在 finally 里成对调用 {@link #endTurn()}）
     */
    public boolean beginTurn() {
        if (!dynamicEnabled()) return false;
        if (activeTurns.incrementAndGet() == 1) {
            String resolved = resolve();
            if (resolved != null && !resolved.isBlank() && !resolved.equals(pinned)) {
                log.info("[AgentScope] 默认模型已切换（闲置时生效）: {} → {}", pinned, resolved);
                pinned = resolved;
            }
        }
        return true;
    }

    /** 结束一轮：计数归零后，下一轮 begin 会重新捡起网关默认模型 */
    public void endTurn() {
        activeTurns.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /** 当前生效（本轮钉住）的模型名；还没跑过任何一轮时按当前默认初始化 */
    public String currentName() {
        String value = pinned;
        if (value != null && !value.isBlank()) return value;
        synchronized (this) {
            if (pinned == null || pinned.isBlank()) {
                String resolved = resolve();
                pinned = resolved == null || resolved.isBlank()
                        ? properties.getFallbackModel() : resolved;
            }
            return pinned;
        }
    }

    /** 是否启用动态默认模型：显式配了 {@code agentscope.model} 就以配置为准（部署钉死） */
    public boolean dynamicEnabled() {
        if (!properties.isDynamicDefaultModel()) return false;
        String configured = properties.getModel();
        return configured == null || configured.isBlank();
    }

    /** 解析网关默认文本模型（Redis 优先 → HTTP → 硬编码兜底，与旧的启动期解析同一口径） */
    private String resolve() {
        try {
            return modelRouter.resolve("creative.plan");
        } catch (Exception e) {
            log.warn("[AgentScope] 解析默认模型失败，沿用上一个: {}", e.getMessage());
            return null;
        }
    }
}
