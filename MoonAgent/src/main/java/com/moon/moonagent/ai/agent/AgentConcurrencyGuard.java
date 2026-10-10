package com.moon.moonagent.ai.agent;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounds model/media fan-out across conversations and within one user.
 */
@Component
public class AgentConcurrencyGuard {

    private final AgentAiConfigProperties properties;
    private final Semaphore global;
    private final ConcurrentHashMap<String, Semaphore> perUser = new ConcurrentHashMap<>();

    public AgentConcurrencyGuard(AgentAiConfigProperties properties) {
        this.properties = properties;
        this.global = new Semaphore(Math.max(1, properties.getGlobalConcurrency()), true);
    }

    public <T> T call(String userId, Supplier<T> action) {
        Semaphore user = userId == null || userId.isBlank()
                ? null
                : perUser.computeIfAbsent(userId, ignored ->
                new Semaphore(Math.max(1, properties.getPerUserConcurrency()), true));
        boolean userAcquired = false;
        boolean globalAcquired = false;
        try {
            long timeout = Math.max(1, properties.getConcurrencyAcquireTimeoutSeconds());
            if (user != null) {
                userAcquired = acquire(user, timeout);
                if (!userAcquired) throw new IllegalStateException("用户并发已满，请稍后重试");
            }
            globalAcquired = acquire(global, timeout);
            if (!globalAcquired) throw new IllegalStateException("系统并发已满，请稍后重试");
            return action.get();
        } finally {
            if (globalAcquired) global.release();
            if (userAcquired && user != null) {
                user.release();
                if (user.availablePermits() >= Math.max(1, properties.getPerUserConcurrency())
                        && !user.hasQueuedThreads()) {
                    perUser.remove(userId, user);
                }
            }
        }
    }

    private static boolean acquire(Semaphore semaphore, long timeoutSeconds) {
        try {
            return semaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发许可被中断", interrupted);
        }
    }
}
