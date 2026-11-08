package com.payguard.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void bucketAllowsBurstThenRefillsAtTheConfiguredRate() {
        TokenBucket b = new TokenBucket(10, 20, 0);
        for (int i = 0; i < 20; i++) assertThat(b.tryConsume(0)).isTrue();   // burst = capacity
        assertThat(b.tryConsume(0)).isFalse();                                 // empty
        assertThat(b.tryConsume(SECOND * 15 / 100)).isTrue();                        // 0.15s -> 1.5 tokens, take 1
        assertThat(b.tryConsume(SECOND * 15 / 100)).isFalse();
        assertThat(b.secondsUntilNextToken()).isEqualTo(1);
        // A long pause never overfills beyond capacity.
        for (int i = 0; i < 20; i++) assertThat(b.tryConsume(100 * SECOND)).isTrue();
        assertThat(b.tryConsume(100 * SECOND)).isFalse();
    }

    @Test
    void filterReturns429WithRetryAfterPerClient() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 1);   // 1/s, burst 2
        int ok = 0, limited = 0;
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse res = call(filter, "/api/webhooks/payguard-sim", "10.0.0.1");
            if (res.getStatus() == 429) {
                limited++;
                assertThat(res.getHeader("Retry-After")).isNotNull();
                assertThat(res.getContentAsString()).contains("RATE_LIMITED");
            } else {
                ok++;
            }
        }
        assertThat(ok).isEqualTo(2);
        assertThat(limited).isEqualTo(3);
        // Another client is not affected; health checks are never limited.
        assertThat(call(filter, "/api/webhooks/payguard-sim", "10.0.0.2").getStatus()).isEqualTo(200);
        assertThat(call(filter, "/actuator/health", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void disabledFilterLetsEverythingThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(false, 1, 1);
        for (int i = 0; i < 10; i++) assertThat(call(filter, "/api/ops/summary", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse call(RateLimitFilter f, String uri, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
