package com.payguard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client rate limiting (token bucket per client IP and route class).
 *
 * <p>Why in memory and not Redis: one instance needs no shared state, and losing the counters on
 * restart is harmless (it only resets limits). With several instances behind a load balancer, each
 * instance limits independently (effective limit = N x configured); moving the buckets to Redis
 * would make the limit global. That is the one place Redis would be justified here - it is never
 * used for financial state or idempotency.
 *
 * <p>Memory safety: idle buckets are evicted, and the map is capped so a flood of spoofed source
 * addresses cannot grow it without bound.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_BUCKETS = 100_000;

    private final boolean enabled;
    private final double webhookRate;
    private final double apiRate;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${payguard.rate-limit.enabled:true}") boolean enabled,
                           @Value("${payguard.rate-limit.webhooks-per-second:200}") double webhookRate,
                           @Value("${payguard.rate-limit.api-per-second:50}") double apiRate) {
        this.enabled = enabled;
        this.webhookRate = webhookRate;
        this.apiRate = apiRate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !enabled || !(path.startsWith("/api/") || path.startsWith("/sim/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean webhook = request.getRequestURI().startsWith("/api/webhooks/");
        double rate = webhook ? webhookRate : apiRate;
        // Behind a reverse proxy configure server.forward-headers-strategy so getRemoteAddr() is the real client.
        String key = (webhook ? "wh:" : "api:") + request.getRemoteAddr();
        long now = System.nanoTime();
        if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(key)) {
            evictIdle(now, 0);
        }
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(rate, rate * 2, now));
        if (bucket.tryConsume(now)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(Math.max(1, bucket.secondsUntilNextToken())));
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\",\"details\":[]}");
    }

    @Scheduled(fixedDelay = 60_000)
    void evictIdleBuckets() {
        evictIdle(System.nanoTime(), 600_000_000_000L); // idle > 10 minutes
    }

    private void evictIdle(long now, long idleNanos) {
        buckets.entrySet().removeIf(e -> now - e.getValue().lastUsedNanos() > idleNanos);
    }

    int bucketCount() {
        return buckets.size();
    }
}
