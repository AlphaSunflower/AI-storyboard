package com.moon.moonagent.ai.agent.turn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 一个在途回合的 steer 队列。
 *
 * <p>HTTP 线程写入、AgentScope 的 reasoning 线程消费；{@link ConcurrentLinkedQueue} 保证
 * 两端不需要额外加锁。消息一旦被 drain 就只注入一次，后续步骤从 {@code AgentState}
 * 读取，不会重复。
 */
public final class TurnSteerInbox {

    private final ConcurrentLinkedQueue<TurnSteerMessage> queue = new ConcurrentLinkedQueue<>();

    /** 入队；null 与空正文直接忽略。 */
    public void offer(TurnSteerMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return;
        }
        queue.add(message);
    }

    /** 取出当前所有待注入消息；没有时返回空表。 */
    public List<TurnSteerMessage> drain() {
        List<TurnSteerMessage> drained = new ArrayList<>();
        TurnSteerMessage message;
        while ((message = queue.poll()) != null) {
            drained.add(message);
        }
        return drained;
    }

    /** 当前待注入条数（用于日志/断言）。 */
    public int size() {
        return queue.size();
    }
}
