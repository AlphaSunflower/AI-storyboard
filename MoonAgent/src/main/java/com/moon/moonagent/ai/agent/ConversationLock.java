package com.moon.moonagent.ai.agent;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话级互斥锁（Redis 分布式版本）：同一 conversation 同时只允许一个活跃编排实例。
 *
 * <p>使用 Redis SETNX 实现原子锁，TTL 自动过期防死锁。
 * 获取失败 → SSE error 40901「当前对话正在处理中，请稍候」。
 */
@Component
@RequiredArgsConstructor
public class ConversationLock {

    private static final Logger log = LoggerFactory.getLogger(ConversationLock.class);

    private final StringRedisTemplate redisTemplate;

    private static final String PREFIX = "agent:lock:conversation:";
    private static final long TTL_SECONDS = 1800;
    private static final long RENEW_SECONDS = 30;
    private static final ThreadLocal<Map<String, String>> THREAD_LOCKS = ThreadLocal.withInitial(ConcurrentHashMap::new);
    private static final ScheduledExecutorService RENEWER =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    public LockLease acquire(String conversationId) {
        String key = PREFIX + conversationId;
        String owner = UUID.randomUUID().toString();
        try {
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(key, owner, TTL_SECONDS, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(ok)) return null;
            return new LockLease(conversationId, owner);
        } catch (Exception e) {
            log.error("ConversationLock: Redis 不可用，拒绝并发会话 conversationId={}", conversationId, e);
            return null;
        }
    }

    /**
     * 尝试获取会话锁（原子 SETNX，无阻塞等待）。
     * @return true=获取成功；false=已有编排实例在跑
     */
    public boolean tryAcquire(String conversationId) {
        LockLease lease = acquire(conversationId);
        if (lease == null) {
            log.debug("ConversationLock: 会话 {} 已被锁定", conversationId);
            return false;
        }
        THREAD_LOCKS.get().put(conversationId, lease.owner());
        return true;
    }

    /** 释放会话锁 */
    public void release(String conversationId) {
        String owner = THREAD_LOCKS.get().remove(conversationId);
        if (owner == null) {
            log.warn("ConversationLock: 当前线程未持有会话锁 conversationId={}", conversationId);
            return;
        }
        release(conversationId, owner);
    }

    public boolean release(String conversationId, String owner) {
        try {
            Long released = redisTemplate.execute(
                    RELEASE_SCRIPT, List.of(PREFIX + conversationId), owner);
            return released != null && released > 0;
        } catch (Exception e) {
            log.warn("ConversationLock: 释放锁失败 conversationId={} error={}", conversationId, e.getMessage());
            return false;
        }
    }

    /** 释放锁并移除条目（语义同 release，Redis 无内存条目需清理） */
    public void releaseAndRemove(String conversationId) {
        try {
            redisTemplate.delete(PREFIX + conversationId);
        } catch (Exception e) {
            log.warn("ConversationLock: 删除会话锁失败 conversationId={}", conversationId);
        }
    }

    /**
     * 在指定时间内轮询获取会话锁。用于“打断并发送”：先置打断标志，
     * 再等待旧编排安全收尾并原子接管锁。
     */
    public boolean acquireWithTimeout(String conversationId, Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        do {
            if (tryAcquire(conversationId)) return true;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    /** Read-only lock probe used by wakeup scheduling and diagnostics. */
    public boolean isLocked(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + conversationId));
        } catch (Exception e) {
            return false;
        }
    }

    public final class LockLease implements AutoCloseable {
        private final String conversationId;
        private final String owner;
        private final java.util.concurrent.ScheduledFuture<?> renewal;
        private volatile boolean closed;

        private LockLease(String conversationId, String owner) {
            this.conversationId = conversationId;
            this.owner = owner;
            this.renewal = RENEWER.scheduleAtFixedRate(
                    this::renew,
                    RENEW_SECONDS,
                    RENEW_SECONDS,
                    TimeUnit.SECONDS);
        }

        public String owner() {
            return owner;
        }

        private void renew() {
            if (closed) return;
            try {
                redisTemplate.execute(
                        RENEW_SCRIPT,
                        List.of(PREFIX + conversationId),
                        owner,
                        String.valueOf(TTL_SECONDS));
            } catch (Exception e) {
                log.warn("ConversationLock: 续租失败 conversationId={}", conversationId);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            renewal.cancel(false);
            release(conversationId, owner);
        }
    }
}
