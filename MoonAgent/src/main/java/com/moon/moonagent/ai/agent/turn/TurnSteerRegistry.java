package com.moon.moonagent.ai.agent.turn;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在途回合的 steer 注册表。
 *
 * <p>回合建立有序 publisher 之后登记，回合收尾（终态事件写完后）注销。HTTP 线程只在这里
 * 查找当前回合；找不到就说明回合已结束，调用方应改走普通发送（自动开新一轮）。
 *
 * <p>注册表只在单 JVM 内有效：同一会话由 Redis 会话锁保证串行，因此一个 conversationId
 * 同时最多一个活跃回合。
 *
 * <p>残留携带：回合结束时队列里仍有未被模型读取的消息（极端竞态），不丢弃，
 * 记到 {@code pending}，下一次 {@link #open} 时注入新回合的开头。
 */
@Component
public class TurnSteerRegistry {

    private final Map<String, ActiveTurnSteer> active = new ConcurrentHashMap<>();
    private final Map<String, List<TurnSteerMessage>> pending = new ConcurrentHashMap<>();

    /**
     * 登记一个在途回合。若同会话已有旧回合（异常收尾未注销），直接覆盖，
     * 避免旧 publisher 把 steer 事件写到已经结束的 turn。
     *
     * <p>上一回合未被消费的 steer 会在这里转交给新回合。
     */
    public ActiveTurnSteer open(String conversationId, String turnId,
                                AgentTurnEventPublisher publisher) {
        TurnSteerInbox inbox = new TurnSteerInbox();
        List<TurnSteerMessage> carried = conversationId == null ? null : pending.remove(conversationId);
        if (carried != null) {
            carried.forEach(inbox::offer);
        }
        ActiveTurnSteer entry = new ActiveTurnSteer(conversationId, turnId, inbox, publisher);
        if (conversationId != null) {
            active.put(conversationId, entry);
        }
        return entry;
    }

    /** 查找当前活跃回合；不存在返回 null。 */
    public ActiveTurnSteer find(String conversationId) {
        return conversationId == null ? null : active.get(conversationId);
    }

    /** 返回当前活跃回合的 inbox；不存在返回 null。 */
    public TurnSteerInbox inboxFor(String conversationId) {
        ActiveTurnSteer entry = find(conversationId);
        return entry == null ? null : entry.inbox();
    }

    /**
     * 注销回合。只有 turnId 匹配时才删除，防止旧回合的 finally 误删新回合。
     * 未消费的消息转入 pending，下一次 open 时注入。
     */
    public void close(String conversationId, String turnId) {
        if (conversationId == null || turnId == null) {
            return;
        }
        active.computeIfPresent(conversationId, (key, entry) -> {
            if (!entry.turnId().equals(turnId)) {
                return entry;
            }
            carryOver(conversationId, entry.inbox().drain());
            return null;
        });
    }

    /** 当前等待带入下一轮的 steer 条数（诊断/测试用）。 */
    public int pendingCount(String conversationId) {
        List<TurnSteerMessage> list = conversationId == null ? null : pending.get(conversationId);
        return list == null ? 0 : list.size();
    }

    private void carryOver(String conversationId, List<TurnSteerMessage> leftover) {
        if (leftover == null || leftover.isEmpty()) {
            return;
        }
        pending.compute(conversationId, (key, existing) -> {
            List<TurnSteerMessage> merged = existing == null ? new ArrayList<>() : existing;
            merged.addAll(leftover);
            return merged;
        });
    }
}
