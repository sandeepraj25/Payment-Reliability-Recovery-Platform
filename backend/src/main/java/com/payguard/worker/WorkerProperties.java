package com.payguard.worker;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param maxAttempts attempts per cycle before an event is dead-lettered (a manual replay starts a new cycle)
 * @param baseDelay   first retry delay (before jitter)
 * @param maxDelay    cap for the exponential growth
 */
@Validated
@ConfigurationProperties(prefix = "payguard.worker")
public record WorkerProperties(@DefaultValue("true") boolean enabled,
                               @DefaultValue("5") @Min(1) int maxAttempts,
                               @DefaultValue("PT1S") @NotNull Duration baseDelay,
                               @DefaultValue("PT1M") @NotNull Duration maxDelay) {

    public RetryPolicy retryPolicy() {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay);
    }
}
