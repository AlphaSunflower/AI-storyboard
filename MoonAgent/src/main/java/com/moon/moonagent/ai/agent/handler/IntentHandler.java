package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.entity.AgentCheckpoint;

import java.util.Set;

/**
 * 意图处理器（策略模式注册机制）。
 *
 * <p>Orchestrator 只做两件事：{@code run()} 按意图标识分发到 {@link #handle}，
 * {@code resume()} 按 checkpoint.action 分发到 {@link #resume}。
 * 新增意图 = 新实现类 + @Component，核心编排零改动。
 */
public interface IntentHandler {

    /** 本处理器负责的意图标识（intent-aisplit 等） */
    String intentType();

    /** resume 阶段本处理器认领的 checkpoint action 集合（agree / generate_image / generate_video） */
    Set<String> resumeActions();

    /**
     * 主链路执行（含 HITL 暂停点：方案 → checkpoint 落库 → human_input/video_plan 事件 → 结束本轮）。
     *
     * @return 本轮最后一条 message 内容（供调用方落库 assistant 消息；无消息返回空串）
     */
    String handle(OrchestrationRequest request);

    /**
     * HITL 表单提交后续流（checkpoint 已由 Orchestrator 校验并一次性消费）。
     * 执行对应工具 → confirm_result → message_end。
     *
     * @return 本轮最后一条 message 内容（供调用方落库 assistant 消息；无消息返回空串）
     */
    String resume(OrchestrationRequest request, AgentCheckpoint checkpoint);
}
