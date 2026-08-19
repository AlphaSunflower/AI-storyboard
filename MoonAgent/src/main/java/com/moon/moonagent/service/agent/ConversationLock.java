package com.moon.moonagent.service.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 会话级互斥锁：同一 conversation 同时只允许一个活跃编排实例。
 *
 * <p>约定：锁获取在 Controller 线程同步完成（runAsync 调度之前，防双实例同时启动），
 * 释放发生在异步任务线程 finally——故用 {@link Semaphore}（无线程所有权语义，
 * 跨线程 release 安全；ReentrantLock 会因 isHeldByCurrentThread 守卫导致锁残留）。
 * 获取失败 → SSE error 40901「当前对话正在处理中，请稍候」/ blocking 抛 40901。
 *
 * <p>ponytail: JVM 内锁，单实例部署正确；多实例部署需换 PostgreSQL SELECT ... FOR UPDATE
 * 或 Redis 分布式锁（当前 pom 无 Redis 依赖，暂不预建）。
 */
@Component
public class ConversationLock {

    private final ConcurrentHashMap<String, Semaphore> locks = new ConcurrentHashMap<>();

    /** 尝试获取会话锁（3s 超时）；失败返回 false（已有编排实例在跑） */
    public boolean tryAcquire(String conversationId) {
        Semaphore semaphore = locks.computeIfAbsent(conversationId, k -> new Semaphore(1));
        try {
            return semaphore.tryAcquire(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 释放会话锁（仅 tryAcquire 成功路径调用；Semaphore 无所有权，跨线程安全） */
    public void release(String conversationId) {
        Semaphore semaphore = locks.get(conversationId);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /** 释放锁并移除条目（会话删除时调用，防止内存泄漏） */
    public void releaseAndRemove(String conversationId) {
        Semaphore semaphore = locks.remove(conversationId);
        if (semaphore != null) {
            semaphore.release();
        }
    }
}
