package com.moon.moonagent.ai.agent.orchestration;

import java.util.Map;

/**
 * 单个子智能体节点的执行器。真实实现负责构造受限上下文的子 Agent；
 * 测试/离线场景可以换成任意实现。
 */
@FunctionalInterface
public interface SubagentNodeExecutor {

    SubagentTaskResult execute(SubagentTask task,
                               Map<String, SubagentTaskResult> dependencies,
                               SubagentCancellation cancellation) throws Exception;

    /**
     * 带整图共享上下文的执行入口。默认实现忽略 context，保持现有测试/离线执行器兼容。
     */
    default SubagentTaskResult execute(SubagentTask task,
                                       Map<String, SubagentTaskResult> dependencies,
                                       SubagentCancellation cancellation,
                                       String context) throws Exception {
        return execute(task, dependencies, cancellation);
    }
}
