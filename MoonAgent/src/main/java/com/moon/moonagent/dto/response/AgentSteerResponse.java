package com.moon.moonagent.dto.response;

/**
 * steer 结果。
 *
 * <p>{@code status = steered}：消息已插入当前回合，事件已走同一 turn 的有序通道；
 * {@code status = not_running}：回合已结束，前端应改走普通发送（自动开新一轮）。
 *
 * @param status     {@code steered} / {@code not_running}
 * @param turnId     目标回合
 * @param messageId  已落库的用户消息 ID；{@code not_running} 时为 null
 */
public record AgentSteerResponse(String status, String turnId, String messageId) {

    public static final String STEERED = "steered";
    public static final String NOT_RUNNING = "not_running";

    public static AgentSteerResponse steered(String turnId, String messageId) {
        return new AgentSteerResponse(STEERED, turnId, messageId);
    }

    public static AgentSteerResponse notRunning(String turnId) {
        return new AgentSteerResponse(NOT_RUNNING, turnId, null);
    }
}
