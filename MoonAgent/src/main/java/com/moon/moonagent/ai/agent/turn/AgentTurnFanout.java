package com.moon.moonagent.ai.agent.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import com.moon.moonagent.entity.AgentTurnEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 一个 turn 的事件扇出中心：让"刷新 / 新开页面"的 SSE 连接能接上**正在运行**的 turn。
 *
 * <p><b>红线 1 不受影响</b>：本类不写事件、不分配序号、不做批次合并。它只广播
 * {@link AgentTurnEventPublisher} 已经落库并已经推送给主连接的那些 {@link AgentTurnEventEnvelope}，
 * 因此 turn 事件仍然只有一条有序写入通道。
 *
 * <p><b>零窗口</b>：{@link #register} 在 <b>per-turn 锁内</b>把 {@code afterSeq} 之后的历史读出来并入队，
 * 再把订阅者挂进订阅表（去重水位推进到历史的最大序号）；{@link #publish} 也在同一把锁上排队。
 * 因此订阅者队列里永远是"先历史、后直播"，不会出现直播插入历史之前造成的序号缺口。
 * 代价是注册期间持锁做一次 {@code agent_turn_events(turn_id, sequence)} 索引查询，
 * publish 最多因此等一次查询（毫秒级），换历史与直播之间严格无窗口。
 * 因为 publisher 是"先落库、再回调"，所以任一时刻：
 * <ul>
 *   <li>publish 先于 register 拿到锁 → 该事件已在库里，register 的历史补发必然包含它；</li>
 *   <li>publish 后于 register 拿到锁 → 该事件走直播投递，且被订阅者的 {@code lastSeq} 去重保护。</li>
 * </ul>
 * 两条路径覆盖全部时序，历史与直播之间不会丢事件、也不会重复投递。
 *
 * <p><b>不阻塞 turn</b>：publish 路径只做入队 + 唤起本订阅者的串行执行器，真正的
 * {@code SseEmitter.send} 在订阅者自己的虚拟线程上执行，慢客户端 / 断开客户端都不会把
 * turn 线程拖住；发送失败、队列积压超限、订阅者数量超限都只摘掉该订阅者（{@code log.warn}），
 * 异常一律吞掉，绝不影响 publisher 主流程。
 */
@Component
public final class AgentTurnFanout {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnFanout.class);

    /** 同一 turn 允许的并发订阅者上限（正常场景只有 1 个重连窗口）。 */
    static final int DEFAULT_MAX_SUBSCRIBERS = 4;
    /** 单个订阅者的待发队列上限，超过即为慢客户端，直接摘除，避免内存无界增长。 */
    static final int DEFAULT_MAX_QUEUE_DEPTH = 256;
    /** 已关闭 turn 的墓碑数量上限：只用于覆盖"状态刚查完就终态"的竞态，不需要长期保留。 */
    private static final int CLOSED_TOMBSTONE_LIMIT = 512;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentTurnEventStore store;
    private final int maxSubscribers;
    private final int maxQueueDepth;
    private final Consumer<Runnable> dispatcher;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicLong subscriberIds = new AtomicLong();
    /**
     * 已终态的 turnId 墓碑（有界）。订阅者在"查到 running → register"之间遇到 turn 收尾时，
     * 靠它立刻补完历史并关闭连接，而不是挂到 SSE 超时。
     */
    private final Map<String, Boolean> closedTurns = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > CLOSED_TOMBSTONE_LIMIT;
                }
            });

    /**
     * 一个 turn 的终止事件：补历史时若已看到它们，说明本次直播不会再有后续事件，直接收尾。
     * （HITL 暂停同样结束本次直播，前端靠 {@code afterSeq} 重放恢复确认卡片。）
     */
    private static final java.util.Set<String> TERMINAL_TYPES = java.util.Set.of(
            "turn_completed", "turn_failed", "turn_paused", "canvas_session_required");

    @Autowired
    public AgentTurnFanout(AgentTurnEventStore store) {
        this(store, DEFAULT_MAX_SUBSCRIBERS, DEFAULT_MAX_QUEUE_DEPTH, AgentTurnFanout::startVirtualThread);
    }

    /** 测试用构造器：{@code dispatcher} 传 {@code Runnable::run} 即为完全同步、可断言的投递。 */
    AgentTurnFanout(AgentTurnEventStore store,
                    int maxSubscribers,
                    int maxQueueDepth,
                    Consumer<Runnable> dispatcher) {
        this.store = store;
        this.maxSubscribers = Math.max(1, maxSubscribers);
        this.maxQueueDepth = Math.max(1, maxQueueDepth);
        this.dispatcher = dispatcher == null ? AgentTurnFanout::startVirtualThread : dispatcher;
    }

    /**
     * 注册一个订阅者：先补 {@code afterSeq} 之后的历史事件，再持续接收直播事件。
     *
     * <p>订阅者数量超限或该 turn 已经终态时，只补历史随后关闭连接（不挂住）。
     *
     * @param turnId   轮次 ID
     * @param emitter  该订阅连接；断开只注销自己，绝不中断 turn
     * @param afterSeq 客户端已经收到的最大序号；从它之后开始补
     */
    public void register(String turnId, SseEmitter emitter, long afterSeq) {
        if (turnId == null || turnId.isBlank() || emitter == null) return;
        long from = Math.max(0L, afterSeq);
        boolean alreadyClosed = closedTurns.containsKey(turnId);
        Channel channel = alreadyClosed ? null : channels.computeIfAbsent(turnId, Channel::new);
        Subscriber subscriber = new Subscriber(subscriberIds.incrementAndGet(), emitter, turnId, channel);

        List<AgentTurnEventEnvelope> history;
        boolean terminate;
        boolean overflow = false;
        if (channel == null) {
            // 该 turn 已收尾：只补历史，不建订阅。
            history = safeHistory(turnId, from);
            for (AgentTurnEventEnvelope envelope : history) {
                subscriber.enqueueHistory(envelope);
            }
            terminate = true;
        } else {
            synchronized (channel.lock) {
                // 锁内读历史 + 锁内入队：publish 在同一把锁上排队，因此订阅者队列里
                // 永远是"先历史、后直播"，不可能出现直播事件插到历史前面导致序号缺口。
                history = safeHistory(turnId, from);
                long last = from;
                for (AgentTurnEventEnvelope envelope : history) {
                    last = Math.max(last, envelope.seq());
                }
                subscriber.lastSeq = last;
                for (AgentTurnEventEnvelope envelope : history) {
                    subscriber.enqueueHistory(envelope);
                }
                if (channel.closed || closedTurns.containsKey(turnId)
                        || (endsWithTerminal(history) && !isRunning(turnId))) {
                    // 三种情况都说明"本次直播不会再有任何事件"，补完历史就关闭，绝不挂到 SSE 超时：
                    // 1) channel 已被 complete 关闭；2) 有终态墓碑；3) 历史末尾已是终止事件且 turn
                    // 不在运行中（墓碑被容量淘汰后的兜底）。
                    // 反过来：历史末尾虽是 turn_paused，但 turn 已被 resume 回到 running（同一 turnId
                    // 续跑）时必须继续订阅，否则刷新会再次"看不到直播"。
                    terminate = true;
                } else if (channel.subscribers.size() >= maxSubscribers) {
                    terminate = true;
                    overflow = true;
                } else {
                    channel.subscribers.put(subscriber.id, subscriber);
                    if (subscriber.isFailed()) {
                        // 补历史时发送就失败了（inline 派发）：立刻摘除，别让它占着订阅名额。
                        channel.remove(subscriber, "补历史发送失败");
                        terminate = true;
                    } else {
                        terminate = false;
                    }
                }
            }
        }
        if (overflow) {
            log.warn("[turn-fanout] 订阅者超过上限 {}，只补历史后关闭 turnId={}", maxSubscribers, turnId);
        }
        if (terminate) {
            subscriber.finish();
        }
    }

    /**
     * 广播一个 publisher 已经落库并推送过的事件。
     *
     * <p>没有任何订阅者时是零成本空操作（不建 channel、不分配对象）。
     * 本方法吞掉全部异常，绝不把订阅侧的故障带回 turn 主流程。
     */
    public void publish(String turnId, AgentTurnEventEnvelope envelope) {
        if (turnId == null || turnId.isBlank() || envelope == null) return;
        try {
            Channel channel = channels.get(turnId);
            if (channel == null) return;
            channel.broadcast(envelope);
        } catch (RuntimeException error) {
            log.warn("[turn-fanout] 广播失败（已忽略，不影响 turn） turnId={} seq={}: {}",
                    turnId, envelope.seq(), error.getMessage());
        }
    }

    /**
     * 本次直播收尾：关闭并移除该 turn 的全部订阅者。
     *
     * <p>turn 完成 / 失败 / 取消 / HITL 暂停都必须调用（暂停同样结束本次直播，
     * 前端靠 {@code afterSeq} 重放恢复确认卡片）。
     *
     * <p>只有"该 turnId 不会再产生事件"时才留墓碑：HITL 暂停（{@code waiting_user}）之后
     * 用户可以 resume，续跑复用<b>同一个 turnId</b>，留墓碑会把续跑期间的刷新订阅直接关掉。
     */
    public void complete(String turnId) {
        if (turnId == null || turnId.isBlank()) return;
        Channel channel = channels.remove(turnId);
        if (channel != null) {
            for (Subscriber subscriber : channel.close()) {
                subscriber.finish();
            }
        }
        if (isResumable(turnId)) return;
        closedTurns.put(turnId, Boolean.TRUE);
    }

    /** 该 turn 是否仍可能产生事件（waiting_user = 等待用户，resume 后复用同一 turnId 续跑）。 */
    private boolean isResumable(String turnId) {
        try {
            AgentTurnEntity turn = store.find(turnId);
            return turn != null && "waiting_user".equals(turn.getStatus());
        } catch (RuntimeException error) {
            log.warn("[turn-fanout] 读取轮次状态失败（按不可续跑处理）turnId={}: {}",
                    turnId, error.getMessage());
            return false;
        }
    }

    /** 该 turn 当前是否处于运行中（只在历史末尾是终止事件、需要判定能否继续订阅时查询）。 */
    private boolean isRunning(String turnId) {
        try {
            AgentTurnEntity turn = store.find(turnId);
            return turn != null && "running".equals(turn.getStatus());
        } catch (RuntimeException error) {
            log.warn("[turn-fanout] 读取轮次状态失败（按已结束处理）turnId={}: {}",
                    turnId, error.getMessage());
            return false;
        }
    }

    /** 注销单个订阅者（该 SSE 连接自己断开/超时时调用）。 */
    public void unregister(String turnId, SseEmitter emitter) {
        if (turnId == null || emitter == null) return;
        Channel channel = channels.get(turnId);
        if (channel == null) return;
        Subscriber target;
        synchronized (channel.lock) {
            target = channel.subscribers.values().stream()
                    .filter(subscriber -> subscriber.emitter == emitter)
                    .findFirst()
                    .orElse(null);
            if (target == null) return;
            channel.subscribers.remove(target.id);
        }
        target.abandon();
    }

    /** 当前订阅者数量（诊断与测试用）。 */
    public int subscriberCount(String turnId) {
        Channel channel = turnId == null ? null : channels.get(turnId);
        if (channel == null) return 0;
        synchronized (channel.lock) {
            return channel.subscribers.size();
        }
    }

    /** 该 turn 是否已经收尾（诊断与测试用）。 */
    public boolean isClosed(String turnId) {
        return turnId != null && closedTurns.containsKey(turnId);
    }

    /**
     * 补历史：按 {@code seq > afterSeq} 过滤并按序号升序返回。
     *
     * <p>store 自身已经按 {@code sequence > afterSeq} 升序查询，这里再过滤/排序一次是防御：
     * 多余的旧事件会被当成"新事件"重复投递，而降序输入会被发送去重水位整段丢弃（造成永久缺口）。
     */
    private List<AgentTurnEventEnvelope> safeHistory(String turnId, long afterSeq) {
        try {
            int after = (int) Math.min(Integer.MAX_VALUE, afterSeq);
            List<AgentTurnEventEnvelope> events = store.eventsAfter(turnId, after);
            if (events == null || events.isEmpty()) return List.of();
            return events.stream()
                    .filter(event -> event != null && event.seq() > afterSeq)
                    .sorted(java.util.Comparator.comparingInt(AgentTurnEventEnvelope::seq))
                    .toList();
        } catch (RuntimeException error) {
            log.warn("[turn-fanout] 补历史失败 turnId={} afterSeq={}: {}",
                    turnId, afterSeq, error.getMessage());
            return List.of();
        }
    }

    private static void startVirtualThread(Runnable task) {
        Thread.ofVirtual().name("turn-fanout-sse").start(task);
    }

    /** 历史补发的最后一条是否为终止事件（是则无需继续直播）。 */
    private static boolean endsWithTerminal(List<AgentTurnEventEnvelope> history) {
        if (history.isEmpty()) return false;
        AgentTurnEventEnvelope last = history.get(history.size() - 1);
        return last.type() != null && TERMINAL_TYPES.contains(last.type());
    }

    /**
     * 组装 SSE data 帧：{@code {turnId, seq, type, payload}}。
     *
     * <p><b>与 {@code AgentChatServiceImpl.sendRuntimeEnvelope} 共用同一处组装</b>：两边的帧形状必须
     * 完全一致，否则主连接与"刷新后接入"的订阅连接会收到不同的 data 形状。{@code payloadJson} 存在时
     * 用 {@link RawValue} 原样内嵌（保持已有语义，不重新序列化），否则把 payload Map 转成 JSON 节点。
     */
    public static String frameJson(AgentTurnEventEnvelope envelope) {
        try {
            ObjectNode node = JSON.createObjectNode();
            node.put("turnId", envelope.turnId());
            node.put("seq", envelope.seq());
            node.put("type", envelope.type());
            String raw = envelope.payloadJson();
            if (raw != null && !raw.isBlank()) {
                node.putRawValue("payload", new RawValue(raw));
            } else {
                node.set("payload", JSON.valueToTree(
                        envelope.payload() == null ? Map.of() : envelope.payload()));
            }
            return node.toString();
        } catch (RuntimeException error) {
            log.warn("[turn-fanout] 事件帧序列化失败 turnId={} seq={}: {}",
                    envelope.turnId(), envelope.seq(), error.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("turnId", envelope.turnId());
            fallback.put("seq", envelope.seq());
            fallback.put("type", envelope.type());
            fallback.put("payload", Map.of());
            try {
                return JSON.writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{}";
            }
        }
    }

    /** 一个 turn 的订阅表；{@code lock} 同时保护"补历史 + 挂订阅者"与"广播"。 */
    private final class Channel {

        private final String turnId;
        private final Object lock = new Object();
        private final Map<Long, Subscriber> subscribers = new LinkedHashMap<>();
        private boolean closed;

        private Channel(String turnId) {
            this.turnId = turnId;
        }

        private void broadcast(AgentTurnEventEnvelope envelope) {
            List<Subscriber> targets;
            synchronized (lock) {
                if (closed || subscribers.isEmpty()) return;
                targets = new ArrayList<>(subscribers.values());
            }
            for (Subscriber subscriber : targets) {
                if (envelope.seq() <= subscriber.lastSeq) continue;
                if (!subscriber.offer(envelope)) {
                    remove(subscriber, "发送队列积压或连接已断开");
                }
            }
        }

        private List<Subscriber> close() {
            synchronized (lock) {
                closed = true;
                List<Subscriber> drained = new ArrayList<>(subscribers.values());
                subscribers.clear();
                return drained;
            }
        }

        private void remove(Subscriber subscriber, String reason) {
            boolean removed;
            synchronized (lock) {
                removed = subscribers.remove(subscriber.id) != null;
            }
            if (!removed) return;
            subscriber.abandon();
            log.warn("[turn-fanout] 已摘除订阅者 turnId={} subscriberId={} 原因={}",
                    turnId, subscriber.id, reason);
        }
    }

    /**
     * 单个订阅连接：有界队列 + 串行执行器。
     *
     * <p>入队与发送解耦，保证 publisher 线程不会被 {@code SseEmitter.send} 阻塞；
     * 队列由单一执行器按序排空，保证同一连接上的帧顺序与序号一致。
     */
    private final class Subscriber {

        private final long id;
        private final SseEmitter emitter;
        private final String turnId;
        /** 所属订阅表：发送失败时据此把自己摘掉，避免失败连接一直占着订阅名额。 */
        private final Channel channel;
        private final ConcurrentLinkedQueue<AgentTurnEventEnvelope> queue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean draining = new AtomicBoolean(false);
        /**
         * 「已覆盖」水位：历史补发覆盖到的最高序号 + 已经入队广播过的最高序号。
         *
         * <p>只给 {@link Channel#broadcast} 的跳过判定用（已经在历史里或已经入过队的序号不再重复入队）。
         * 注意它<b>不能</b>用于发送去重：历史在 register 阶段就已把水位推到最大，用它做发送判定会把
         * 刚入队的历史整段跳过（真机实测：历史 [1,2] + 直播 [3] 只发出 [3]）。
         */
        private volatile long lastSeq;
        /**
         * 「已发送」水位：只给 {@link #drain} 用。
         *
         * <p>仅在订阅者自己的串行执行器里读写，因此不需要 volatile。
         */
        private long sentSeq;
        private volatile boolean failed;
        private volatile boolean finishRequested;

        private Subscriber(long id, SseEmitter emitter, String turnId, Channel channel) {
            this.id = id;
            this.emitter = emitter;
            this.turnId = turnId;
            this.channel = channel;
        }

        private boolean isFailed() {
            return failed;
        }

        /** @return false 表示队列已满/已失效，调用方应摘除该订阅者 */
        private boolean offer(AgentTurnEventEnvelope envelope) {
            if (failed || finishRequested) return false;
            if (queue.size() >= maxQueueDepth) return false;
            queue.add(envelope);
            // 推进「已覆盖」水位：同一序号不会因为重复广播而被入队两次（发送侧还有 sentSeq 兜底）。
            if (envelope.seq() > lastSeq) lastSeq = envelope.seq();
            schedule();
            return true;
        }

        /**
         * 历史补发入队：不受直播队列上限约束。
         *
         * <p>历史是一次性补齐，被上限截断会在补发与直播之间留下永久缺口；
         * 因此这里允许队列超过 {@code maxQueueDepth}，只用于注册瞬间的一次性回放。
         */
        private boolean enqueueHistory(AgentTurnEventEnvelope envelope) {
            if (failed || finishRequested) return false;
            queue.add(envelope);
            schedule();
            return true;
        }

        /** 请求在排空队列后关闭连接（终态或只补历史）。 */
        private void finish() {
            finishRequested = true;
            schedule();
        }

        /** 放弃该订阅者：不发送、不 complete（连接通常已经由对端关闭）。 */
        private void abandon() {
            failed = true;
            finishRequested = true;
            queue.clear();
        }

        private void schedule() {
            if (draining.compareAndSet(false, true)) {
                try {
                    dispatcher.accept(this::drain);
                } catch (RuntimeException error) {
                    draining.set(false);
                    log.warn("[turn-fanout] 订阅执行器不可用，已放弃 subscriberId={}: {}",
                            id, error.getMessage());
                    abandon();
                }
            }
        }

        private void drain() {
            try {
                AgentTurnEventEnvelope envelope;
                while (!failed && (envelope = queue.poll()) != null) {
                    // 发送去重用 sentSeq（真正发出去的序号），绝不能用 lastSeq：
                    // lastSeq 在 register 阶段已推进到历史最大序号，用它判定会把刚入队的历史整段跳过。
                    if (envelope.seq() <= sentSeq) continue;
                    sentSeq = envelope.seq();
                    if (!send(envelope)) {
                        failed = true;
                        // 发送失败立刻从订阅表摘除（否则要等下一次广播 offer 失败才被清掉）。
                        channel.remove(this, "订阅连接发送失败");
                        break;
                    }
                }
            } catch (RuntimeException error) {
                failed = true;
                log.warn("[turn-fanout] 订阅发送异常 turnId={} subscriberId={}: {}",
                        turnId, id, error.getMessage());
            } finally {
                draining.set(false);
                if (!failed && !queue.isEmpty()) schedule();
            }
            if (failed || finishRequested) {
                queue.clear();
                closeEmitter();
            }
        }

        private boolean send(AgentTurnEventEnvelope envelope) {
            try {
                String name = envelope.type() == null || envelope.type().isBlank()
                        ? "agent_event" : envelope.type();
                emitter.send(SseEmitter.event().name(name).data(frameJson(envelope)));
                return true;
            } catch (Exception error) {
                log.warn("[turn-fanout] 订阅连接发送失败，已摘除 turnId={} subscriberId={}: {}",
                        turnId, id, error.getMessage());
                return false;
            }
        }

        private void closeEmitter() {
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // 对端已断开：complete 失败无副作用。
            }
        }
    }
}
