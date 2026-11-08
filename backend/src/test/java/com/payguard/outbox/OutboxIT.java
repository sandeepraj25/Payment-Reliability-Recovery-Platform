package com.payguard.outbox;

import com.payguard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Module D: transactional outbox. */
class OutboxIT extends IntegrationTestBase {

    @Autowired
    private OutboxPublisher publisher;

    @Test
    void outboxRowIsWrittenOnlyForNewKnownAuthenticEvents() {
        String body = eventJson("evt_ob_1", "payment.captured", "pay_ob", 2, "100", "CAPTURED");
        deliverSigned(body);
        deliverSigned(body);                                                            // duplicate
        deliverSigned("{\"id\":\"evt_ob_u\",\"type\":\"account.updated\"}");            // unknown type
        io.restassured.RestAssured.given().contentType("application/json").body(eventJson("evt_ob_x", "payment.captured", "pay_ob", 2, "100", "CAPTURED"))
                .header("PayGuard-Signature", "t=1,v1=" + "0".repeat(64))
                .post("/api/webhooks/payguard-sim").then().statusCode(401);            // forged

        assertThat(jdbc.sql("""
                SELECT e.provider_event_id FROM outbox_events o JOIN webhook_events e ON e.id = o.webhook_event_id
                 WHERE o.purpose = 'INGEST'
                """).query(String.class).list()).containsExactly("evt_ob_1");
    }

    @Test
    void publishedRowsAreMarkedWithTimestamp() {
        deliverSigned(eventJson("evt_ob_2", "payment.captured", "pay_ob2", 2, "100", "CAPTURED"));
        await().untilAsserted(() -> assertThat(count(
                "SELECT count(*) FROM outbox_events WHERE purpose = 'INGEST' AND status = 'PUBLISHED' AND published_at IS NOT NULL"))
                .isEqualTo(1));
    }

    /**
     * Two publishers (the scheduled one plus 4 extra threads hammering publishOnce) race over 300 rows.
     * With FOR UPDATE SKIP LOCKED every row is published exactly once: each event gets exactly one
     * worker attempt (events are pre-marked PROCESSED, so each message just records one skip).
     */
    @Test
    void concurrentPublishersNeverSendTheSameRowTwice() throws Exception {
        int n = 300;
        jdbc.sql("""
                INSERT INTO webhook_events (provider, provider_event_id, event_type, gateway_payment_id, raw_body, payload,
                                            payload_sha256, processing_status)
                SELECT 'payguard-sim', 'evt_sl_' || g, 'payment.captured', 'pay_sl', '{}', '{}'::jsonb, repeat('a', 64), 'PROCESSED'
                  FROM generate_series(1, :n) g
                """).param("n", n).update();
        jdbc.sql("""
                INSERT INTO outbox_events (webhook_event_id, purpose, routing_key, payload)
                SELECT id, 'INGEST', 'payment.event', json_build_object('webhookEventId', id)::jsonb
                  FROM webhook_events WHERE provider_event_id LIKE 'evt_sl_%'
                """).update();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                while (publisher.publishOnce() > 0) { /* keep draining */ }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        await().untilAsserted(() -> assertThat(count("SELECT count(*) FROM processing_attempts")).isEqualTo(n));
        Thread.sleep(1000);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'")).isEqualTo(n);
        assertThat(count("SELECT count(*) FROM processing_attempts")).isEqualTo(n);   // no row was sent twice
        assertThat(count("SELECT max(c) FROM (SELECT count(*) c FROM processing_attempts GROUP BY webhook_event_id) x"))
                .isEqualTo(1);
    }
}
