package com.moon.moonagent.ai.agent.sandbox;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class SandboxSessionLock {

    private static final String PREFIX = "agent:lock:sandbox:";
    private static final long TTL_SECONDS = 600;
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public SandboxSessionLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public <T> T withLock(String conversationId, Supplier<T> action) {
        String key = PREFIX + conversationId;
        String owner = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue()
                    .setIfAbsent(key, owner, TTL_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException error) {
            throw new MoonSandboxException("Sandbox session lock unavailable", error);
        }
        if (!Boolean.TRUE.equals(acquired)) {
            throw new MoonSandboxException("Sandbox conversation is busy");
        }
        try {
            return action.get();
        } finally {
            try {
                redis.execute(RELEASE, List.of(key), owner);
            } catch (RuntimeException ignored) {
                // TTL releases the lease if Redis is unavailable during cleanup.
            }
        }
    }
}
