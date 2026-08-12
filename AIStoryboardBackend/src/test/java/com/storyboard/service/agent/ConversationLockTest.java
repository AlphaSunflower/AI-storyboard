package com.storyboard.service.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话级互斥锁单测：同一会话同时只允许一个活跃实例；释放后可重获；不同会话互不阻塞。
 * 核心语义（Semaphore 无线程所有权）正是 Agent 编排四入口统一 tryAcquire 的保障。
 */
class ConversationLockTest {

    private final ConversationLock lock = new ConversationLock();

    @Test
    void 同一会话_同时只能一个持有() {
        assertTrue(lock.tryAcquire("c1"));
        // 已持有，再取（即使换线程）也失败——模拟第二个编排实例进入
        assertFalse(lock.tryAcquire("c1"));
        lock.release("c1");
        assertTrue(lock.tryAcquire("c1"));
        lock.release("c1");
    }

    @Test
    void 不同会话_互不阻塞() {
        assertTrue(lock.tryAcquire("c1"));
        assertTrue(lock.tryAcquire("c2"));
        lock.release("c1");
        lock.release("c2");
    }

    @Test
    void 无锁会话_释放不抛异常() {
        assertDoesNotThrow(() -> lock.release("never-acquired"));
    }

    @Test
    void 跨线程释放_锁不残留() throws Exception {
        // 模拟真实场景：获取在 Controller 线程，释放发生在异步任务线程
        assertTrue(lock.tryAcquire("c1"));
        CountDownLatch released = new CountDownLatch(1);
        new Thread(() -> {
            lock.release("c1"); // Semaphore 无所有权，跨线程 release 安全
            released.countDown();
        }).start();
        assertTrue(released.await(2, TimeUnit.SECONDS));
        assertTrue(lock.tryAcquire("c1"), "跨线程释放后锁必须可重获（防锁残留导致永久 40901）");
        lock.release("c1");
    }

    @Test
    void 并发竞争_互斥不丢锁() throws Exception {
        // 8 个线程抢同一会话：Semaphore 语义 = 互斥（同时持有 ≤1）+ 排队（释放后依次成功）。
        // 断言峰值持有数 ≤1（无并发双实例），且全部线程最终拿到锁（无丢失）
        int threads = 8;
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger peakHolders = new AtomicInteger();
        AtomicInteger currentHolders = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    if (lock.tryAcquire("race")) {
                        int now = currentHolders.incrementAndGet();
                        peakHolders.accumulateAndGet(now, Math::max);
                        Thread.sleep(50); // 模拟编排执行
                        // 先递减再释放：确保下一个线程拿到锁时，当前持有计数已归零（防误计峰值）
                        currentHolders.decrementAndGet();
                        lock.release("race");
                        completed.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        go.countDown();
        // 轮询等待全部完成（8 线程 × 50ms 持锁 + 调度开销，300ms 固定睡不够）
        long deadline = System.currentTimeMillis() + 5000;
        while (completed.get() < threads && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(threads, completed.get(), "并发排队后所有实例都应最终完成（锁无丢失）");
        assertTrue(peakHolders.get() <= 1, "任何时刻至多一个实例持有锁（互斥）");
    }
}
