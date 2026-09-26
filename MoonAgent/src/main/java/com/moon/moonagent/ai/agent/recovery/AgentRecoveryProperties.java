package com.moon.moonagent.ai.agent.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "ai.agent.recovery")
public class AgentRecoveryProperties {

    private RecoveryMode mode = RecoveryMode.LEGACY;
    private Duration scanDelay = Duration.ofSeconds(5);
    private int maxParallel = 4;
    private Duration leaseDuration = Duration.ofSeconds(120);
    private int maxAttempts = 5;
    private Duration baseBackoff = Duration.ofSeconds(5);
    private Duration maxBackoff = Duration.ofMinutes(5);

    public RecoveryMode getMode() {
        return mode;
    }

    public void setMode(RecoveryMode mode) {
        this.mode = mode == null ? RecoveryMode.LEGACY : mode;
    }

    public Duration getScanDelay() {
        return scanDelay;
    }

    public void setScanDelay(Duration scanDelay) {
        this.scanDelay = positive(scanDelay, Duration.ofSeconds(5));
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = Math.max(1, maxParallel);
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = positive(leaseDuration, Duration.ofSeconds(120));
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public Duration getBaseBackoff() {
        return baseBackoff;
    }

    public void setBaseBackoff(Duration baseBackoff) {
        this.baseBackoff = positive(baseBackoff, Duration.ofSeconds(5));
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = positive(maxBackoff, Duration.ofMinutes(5));
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
