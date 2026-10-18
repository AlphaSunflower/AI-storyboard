package com.moon.moonagent.ai.agent.agentscope;

/**
 * 子任务执行期间的父级运行上下文。
 *
 * <p>任务图在虚拟线程池里并行跑，不能用普通 ThreadLocal 从工具线程传进去；
 * 编排层在**每个 worker 线程内部** open，再调用子 Agent 执行器，
 * 子 Agent 构造 RuntimeContext 时从这里取 conversation/request/support。
 */
final class SubagentRunScope {

    private static final ThreadLocal<AgentScopeRunContext> CURRENT = new ThreadLocal<>();

    private SubagentRunScope() {
    }

    static Scope open(AgentScopeRunContext context) {
        Scope scope = new Scope(CURRENT.get());
        CURRENT.set(context);
        return scope;
    }

    static AgentScopeRunContext current() {
        return CURRENT.get();
    }

    static final class Scope implements AutoCloseable {
        private final AgentScopeRunContext previous;

        private Scope(AgentScopeRunContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
