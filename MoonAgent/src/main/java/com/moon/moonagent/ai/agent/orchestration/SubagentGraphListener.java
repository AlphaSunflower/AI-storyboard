package com.moon.moonagent.ai.agent.orchestration;

/**
 * 任务图进度监听。实现方负责把节点开始/结束写成当前 turn 的一条 SSE 事件；
 * 监听器异常不能影响调度本身。
 */
public interface SubagentGraphListener {

    SubagentGraphListener NOOP = new SubagentGraphListener() {
        @Override
        public void onStarted(SubagentTask task) {
        }

        @Override
        public void onCompleted(SubagentTask task, SubagentTaskResult result) {
        }
    };

    void onStarted(SubagentTask task);

    void onCompleted(SubagentTask task, SubagentTaskResult result);
}
