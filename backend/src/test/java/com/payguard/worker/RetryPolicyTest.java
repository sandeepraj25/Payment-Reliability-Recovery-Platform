package com.payguard.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30));

    @Test
    void delaysGrowExponentiallyWithinJitterBounds() {
        Random random = new Random(42);
        long[][] expectedRangesMs = {
                {500, 1000},    // attempt 1: exp = 1s
                {1000, 2000},   // attempt 2: exp = 2s
                {2000, 4000},   // attempt 3: exp = 4s
                {4000, 8000},   // attempt 4
                {8000, 16000},  // attempt 5
                {15000, 30000}, // attempt 6: exp capped at 30s
                {15000, 30000}, // attempt 20: still capped
        };
        int[] attempts = {1, 2, 3, 4, 5, 6, 20};
        for (int i = 0; i < attempts.length; i++) {
            for (int sample = 0; sample < 1000; sample++) {
                long ms = policy.delayAfter(attempts[i], random).toMillis();
                assertThat(ms).as("attempt %d", attempts[i]).isBetween(expectedRangesMs[i][0], expectedRangesMs[i][1]);
            }
        }
    }

    @Test
    void jitterActuallySpreadsRetries() {
        // 1,000 messages failing together must NOT all retry at the same millisecond.
        Random random = new Random(7);
        long distinct = java.util.stream.IntStream.range(0, 1000)
                .mapToLong(i -> policy.delayAfter(3, random).toMillis()).distinct().count();
        assertThat(distinct).isGreaterThan(500);
    }

    @Test
    void hugeAttemptNumbersDoNotOverflow() {
        assertThat(policy.delayAfter(Integer.MAX_VALUE, new Random(1))).isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void retryBudget() {
        assertThat(policy.shouldRetry(1)).isTrue();
        assertThat(policy.shouldRetry(4)).isTrue();
        assertThat(policy.shouldRetry(5)).isFalse();   // 5 attempts made -> stop
        assertThat(new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1)).shouldRetry(1)).isFalse();
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ZERO, Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.delayAfter(0, new Random())).isInstanceOf(IllegalArgumentException.class);
    }
}
