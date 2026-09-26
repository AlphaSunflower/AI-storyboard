package com.moon.moonagent.ai.agent.audit;

public final class AgentInvocationContextHolder {

    private static final ThreadLocal<AgentInvocationContext> CURRENT = new ThreadLocal<>();

    private AgentInvocationContextHolder() {
    }

    public static AgentInvocationContext current() {
        AgentInvocationContext context = CURRENT.get();
        return context == null ? AgentInvocationContext.EMPTY : context;
    }

    public static Scope open(AgentInvocationContext context) {
        AgentInvocationContext previous = CURRENT.get();
        CURRENT.set(context == null ? AgentInvocationContext.EMPTY : context);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
