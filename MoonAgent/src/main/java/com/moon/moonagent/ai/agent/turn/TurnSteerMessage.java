package com.moon.moonagent.ai.agent.turn;

import java.time.OffsetDateTime;

/**
 * 一条在途回合中插入的用户消息。
 *
 * @param messageId     产品消息 ID（{@code agent_messages.id}），用于前端去重与回放
 * @param content       正文
 * @param createdAt     入队时间
 */
public record TurnSteerMessage(String messageId, String content, OffsetDateTime createdAt) {
}
