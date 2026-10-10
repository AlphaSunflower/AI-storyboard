package com.moon.moonagent.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential polling backoff with +/-20% jitter.
 */
public final class PollingBackoff {

    private final long initialMillis;
    private final long maxMillis;
    private final double multiplier;

    public PollingBackoff(long initialMillis, long maxMillis, double multiplier) {
        this.initialMillis = Math.max(100L, initialMillis);
        this.maxMillis = Math.max(this.initialMillis, maxMillis);
        this.multiplier = Math.max(1.0, multiplier);
    }

    public static PollingBackoff video() {
        return new PollingBackoff(3_000L, 15_000L, 1.5);
    }

    public long delayMillis(int attempt) {
        double raw = initialMillis * Math.pow(multiplier, Math.max(0, attempt));
        long capped = Math.min(maxMillis, Math.max(initialMillis, (long) raw));
        double jitter = ThreadLocalRandom.current().nextDouble(0.8, 1.2);
        return Math.max(100L, (long) (capped * jitter));
    }
}
