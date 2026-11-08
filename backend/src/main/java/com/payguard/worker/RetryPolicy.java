package com.payguard.worker;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with "equal jitter".
 *
 * <pre>
 *   exp(n)   = min(maxDelay, baseDelay * 2^(n-1))        n = attempt number that just failed (1, 2, 3 ...)
 *   delay(n) = exp(n)/2 + random(0, exp(n)/2)
 *   base 1s: attempt 1 -> 0.5..1s, 2 -> 1..2s, 3 -> 2..4s, 4 -> 4..8s ...
 * </pre>
 *
 * Why backoff: a struggling dependency needs breathing room, not a hammering.
 * Why jitter: 1,000 messages that failed at the same moment must not all retry at the same
 * moment again (thundering herd). Why "equal" rather than "full" jitter: it keeps a minimum
 * gap of exp/2, so a retry never fires almost immediately after the failure.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (baseDelay.isNegative() || baseDelay.isZero()) throw new IllegalArgumentException("baseDelay must be > 0");
        if (maxDelay.compareTo(baseDelay) < 0) throw new IllegalArgumentException("maxDelay must be >= baseDelay");
    }

    /** {@code attemptsSoFar} attempts have failed in this cycle. May we try again? */
    public boolean shouldRetry(int attemptsSoFar) {
        return attemptsSoFar < maxAttempts;
    }

    public Duration delayAfter(int failedAttempt, RandomGenerator random) {
        if (failedAttempt < 1) throw new IllegalArgumentException("failedAttempt must be >= 1");
        long base = baseDelay.toMillis();
        long cap = maxDelay.toMillis();
        int shift = Math.min(failedAttempt - 1, 30);              // avoid overflow of 2^n
        long exp = Math.min(cap, base * (1L << shift));
        long half = exp / 2;
        long jitter = half == 0 ? 0 : random.nextLong(half + 1);  // 0..half inclusive
        return Duration.ofMillis(half + jitter);
    }
}
