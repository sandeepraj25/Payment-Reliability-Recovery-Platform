package com.payguard.config;

/**
 * Classic token bucket: holds up to {@code capacity} tokens, refills at {@code ratePerSecond}.
 * Each request takes one token; no token -> rejected (HTTP 429). Allows short bursts up to
 * capacity while enforcing the average rate. Thread-safe via synchronized (one tiny critical section).
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastRefillNanos;
    private volatile long lastUsedNanos;

    public TokenBucket(double ratePerSecond, double capacity, long nowNanos) {
        if (ratePerSecond <= 0 || capacity < 1) throw new IllegalArgumentException("rate > 0 and capacity >= 1 required");
        this.capacity = capacity;
        this.refillPerNano = ratePerSecond / 1_000_000_000d;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
        this.lastUsedNanos = nowNanos;
    }

    public synchronized boolean tryConsume(long nowNanos) {
        long elapsed = Math.max(0, nowNanos - lastRefillNanos);
        tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
        lastRefillNanos = nowNanos;
        lastUsedNanos = nowNanos;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    /** Seconds until one token is available (for the Retry-After header). */
    public synchronized long secondsUntilNextToken() {
        double missing = Math.max(0, 1 - tokens);
        return (long) Math.ceil(missing / (refillPerNano * 1_000_000_000d));
    }

    public long lastUsedNanos() {
        return lastUsedNanos;
    }
}
