package com.moon.moonagent.ai.agent.orchestration;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务图取消信号。父 turn 被中断时调用 {@link #cancel()}，所有节点应尽快退出。
 */
public final class SubagentCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
    }
}
