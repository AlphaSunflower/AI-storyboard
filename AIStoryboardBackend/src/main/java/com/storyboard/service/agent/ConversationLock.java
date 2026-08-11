package com.storyboard.service.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话级互斥锁：同一 conversation 同时只允许一个活跃编排实例。
 *
 * <p>约定：锁获取必须在 Controller 线程同步完成（runAsync 调度之前，防双实例同时启动），
 * 释放必须在异步任务 finally（含客户端断开 cancel 路径）。
 * 获取失败 → SSE error 40901「当前对话正在处理中，请稍候」/ blocking 抛 40901。
 *
 * <p>ponytail: JVM 内锁，单实例部署正确；多实例部署需换 PostgreSQL SELECT ... FOR UPDATE
 * 或 Redis 分布式锁（当前 pom 无 Redis 依赖，暂不预建）。
 */
@Component
public class ConversationLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 尝试获取会话锁（3s 超时）；失败返回 false（已有编排实例在跑） */
    public boolean tryAcquire(String conversationId) {
        ReentrantLock lock = locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        try {
            return lock.tryLock(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 释放会话锁（须与 tryAcquire 成对，finally 中调用） */
    public void release(String conversationId) {
        ReentrantLock lock = locks.get(conversationId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
