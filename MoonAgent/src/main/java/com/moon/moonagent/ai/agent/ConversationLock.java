package com.moon.moonagent.ai.agent;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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
    /** 5 分钟自动过期——编排最长不超过此值，超时自动释放防死锁 */
    private static final long TTL_SECONDS = 300;

    /**
     * 尝试获取会话锁（原子 SETNX，无阻塞等待）。
     * @return true=获取成功；false=已有编排实例在跑
     */
    public boolean tryAcquire(String conversationId) {
        String key = PREFIX + conversationId;
        try {
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", TTL_SECONDS, TimeUnit.SECONDS);
            boolean acquired = Boolean.TRUE.equals(ok);
            if (!acquired) {
                log.debug("ConversationLock: 会话 {} 已被锁定", conversationId);
            }
            return acquired;
        } catch (Exception e) {
            // Redis 不可用时降级为允许通过（可用性优先）
            log.warn("ConversationLock: Redis 不可用，降级放行 conversationId={} error={}", conversationId, e.getMessage());
            return true;
        }
    }

    /** 释放会话锁 */
    public void release(String conversationId) {
        try {
            redisTemplate.delete(PREFIX + conversationId);
        } catch (Exception e) {
            log.warn("ConversationLock: 释放锁失败 conversationId={} error={}", conversationId, e.getMessage());
        }
    }

    /** 释放锁并移除条目（语义同 release，Redis 无内存条目需清理） */
    public void releaseAndRemove(String conversationId) {
        release(conversationId);
    }
}
