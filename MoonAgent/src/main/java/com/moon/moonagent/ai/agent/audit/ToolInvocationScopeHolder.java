package com.moon.moonagent.ai.agent.audit;

public final class ToolInvocationScopeHolder {

    private static final ThreadLocal<ToolInvocationScope> CURRENT = new ThreadLocal<>();

    private ToolInvocationScopeHolder() {
    }

    public static ToolInvocationScope current() {
        return CURRENT.get();
    }

    public static Scope open(ToolInvocationScope scope) {
        ToolInvocationScope previous = CURRENT.get();
        if (scope == null) CURRENT.remove();
        else CURRENT.set(scope);
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
