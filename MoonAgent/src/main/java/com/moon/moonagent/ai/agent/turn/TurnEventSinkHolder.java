package com.moon.moonagent.ai.agent.turn;

/**
 * 当前 turn 的有序事件发布器。
 *
 * <p>`MoonAgentRuntime` 在构造 `AgentScopeRunContext` 时从这里取 publisher，
 * 工具（如 `run_task_graph`）就能把子任务进度写进同一条 `seq` 通道，
 * 而不是另起一条 SSE 流。
 */
public final class TurnEventSinkHolder {

    private static final ThreadLocal<AgentTurnEventPublisher> CURRENT = new ThreadLocal<>();

    private TurnEventSinkHolder() {
    }

    public static Scope open(AgentTurnEventPublisher publisher) {
        Scope scope = new Scope(CURRENT.get());
        CURRENT.set(publisher);
        return scope;
    }

    public static AgentTurnEventPublisher current() {
        return CURRENT.get();
    }

    public static final class Scope implements AutoCloseable {
        private final AgentTurnEventPublisher previous;

        private Scope(AgentTurnEventPublisher previous) {
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
