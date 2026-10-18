package com.moon.moonagent.ai.agent.turn;

/**
 * 当前正在执行的回合及其 steer 通道。
 *
 * <p>{@code publisher} 用于把 {@code USER_MESSAGE} 事件写进同一 turn 的有序事件流；
 * {@code inbox} 用于在下一个模型步骤注入消息。两者随回合结束一起失效。
 */
public record ActiveTurnSteer(
        String conversationId,
        String turnId,
        TurnSteerInbox inbox,
        AgentTurnEventPublisher publisher
) {
}
