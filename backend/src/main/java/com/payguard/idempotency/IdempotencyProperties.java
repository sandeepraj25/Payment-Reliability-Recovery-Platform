package com.payguard.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param lease how long one request may own a key before a retry is allowed to take over
 * @param ttl   how long a completed response is kept for replay
 */
@ConfigurationProperties(prefix = "payguard.idempotency")
public record IdempotencyProperties(Duration lease, Duration ttl) {
}
