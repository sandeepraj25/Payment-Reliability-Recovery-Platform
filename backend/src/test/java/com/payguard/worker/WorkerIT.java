package com.payguard.worker;

import com.payguard.support.IntegrationTestBase;
import com.payguard.webhook.signature.WebhookSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Module E end to end: gateway -> signed webhook -> PostgreSQL -> outbox -> RabbitMQ -> worker -> order.
 * Real broker, real database, real HTTP. Invariant checked everywhere: one fulfilment per order.
 */
class WorkerIT extends IntegrationTestBase {

    private static final long AMOUNT = 499900;

    @Autowired
    private DeadLetterService deadLetters;

    private long fulfilments(String orderId) {
        return jdbc.sql("SELECT count(*) FROM fulfilments WHERE order_id = CAST(:id AS uuid)").param("id", orderId)
                .query(Long.class).single();
    }

    private String paymentStatus(String paymentId) {
        return jdbc.sql("SELECT status || ':v' || gateway_version FROM payments WHERE id = CAST(:id AS uuid)")
                .param("id", paymentId).query(String.class).single();
    }

    @Test
    void gatewayCaptureFulfilsTheOrderExactlyOnce() {
        CheckoutResult c = checkout("worker-key-0001", AMOUNT);
        gateway(c.gatewayPaymentId(), "authorize");
        gateway(c.gatewayPaymentId(), "capture");

        awaitOrderStatus(c.orderId(), "FULFILLED");
        assertThat(paymentStatus(c.paymentId())).isEqualTo("CAPTURED:v2");
        assertThat(fulfilments(c.orderId())).isEqualTo(1);
        await().untilAsserted(() -> assertThat(count(
                "SELECT count(*) FROM webhook_events WHERE processing_status = 'PROCESSED'")).isEqualTo(2));
        assertThat(count("SELECT count(*) FROM processing_attempts WHERE outcome = 'SUCCESS'")).isEqualTo(2);
    }

    @Test
    void redeliveredWebhooksNeverShipTwice() throws Exception {
        CheckoutResult c = checkout("worker-key-0002", AMOUNT);
        gateway(c.gatewayPaymentId(), "authorize");
        gateway(c.gatewayPaymentId(), "capture");
        awaitOrderStatus(c.orderId(), "FULFILLED");

        String capturedEvent = jdbc.sql("SELECT id FROM gateway.gateway_events WHERE payment_id = :p AND event_type = 'payment.captured'")
                .param("p", c.gatewayPaymentId()).query(String.class).single();
        for (int i = 0; i < 5; i++) {
            admin().post("/sim/gateway/events/" + capturedEvent + "/redeliver").then().statusCode(200);
        }
        Thread.sleep(1500); // give any (wrong) extra processing a chance to happen

        assertThat(fulfilments(c.orderId())).isEqualTo(1);
        assertThat(count("SELECT duplicate_count FROM webhook_events WHERE provider_event_id = '" + capturedEvent + "'")).isEqualTo(5);
        // Duplicates are stopped at ingestion: no new outbox rows, no new worker attempts.
        assertThat(count("SELECT count(*) FROM outbox_events")).isEqualTo(2);
    }

    @Test
    void outOfOrderEventsNeverMovePaymentBackwards() {
        CheckoutResult c = checkout("worker-key-0003", AMOUNT);
        String gp = c.gatewayPaymentId();

        // "captured" (v2) overtakes "authorized" (v1).
        assertThat(deliverSigned(eventJson("evt_ooo_cap", "payment.captured", gp, 2, AMOUNT, "CAPTURED", 0))).isEqualTo(200);
        awaitOrderStatus(c.orderId(), "FULFILLED");
        assertThat(paymentStatus(c.paymentId())).isEqualTo("CAPTURED:v2");

        assertThat(deliverSigned(eventJson("evt_ooo_auth", "payment.authorized", gp, 1, AMOUNT, "AUTHORIZED", 0))).isEqualTo(200);
        awaitEventStatus("evt_ooo_auth", "PROCESSED");

        assertThat(paymentStatus(c.paymentId())).isEqualTo("CAPTURED:v2");   // NOT regressed to AUTHORIZED
        assertThat(attemptOutcomes("evt_ooo_auth")).containsExactly("SKIPPED_STALE");
        assertThat(jdbc.sql("SELECT processing_result FROM webhook_events WHERE provider_event_id = 'evt_ooo_auth'")
                .query(String.class).single()).isEqualTo("STALE_IGNORED");
    }

    @Test
    void thousandConcurrentDuplicateWebhooksProduceOneFulfilment() throws Exception {
        CheckoutResult c = checkout("worker-key-0004", AMOUNT);
        String body = eventJson("evt_k_dup", "payment.captured", c.gatewayPaymentId(), 2, AMOUNT, "CAPTURED", 0);
        String signature = sign(body);

        var pool = Executors.newFixedThreadPool(64);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(pool).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/payguard-sim"))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                .header(WebhookSignature.SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            futures.add(client.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
        pool.shutdown();
        for (var f : futures) {
            assertThat(f.get().statusCode()).isEqualTo(200);
        }

        awaitOrderStatus(c.orderId(), "FULFILLED");
        Thread.sleep(1000);
        assertThat(fulfilments(c.orderId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM webhook_events")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM processing_attempts WHERE outcome = 'SUCCESS'")).isEqualTo(1);
    }

    @Test
    void twoDifferentEventsThatBothMeanPaidShipOnce() {
        CheckoutResult c = checkout("worker-key-0005", AMOUNT);
        String gp = c.gatewayPaymentId();
        // Same version, different event ids: event-level dedup cannot catch this one.
        deliverSigned(eventJson("evt_paid_a", "payment.captured", gp, 2, AMOUNT, "CAPTURED", 0));
        deliverSigned(eventJson("evt_paid_b", "payment.captured", gp, 2, AMOUNT, "CAPTURED", 0));

        awaitEventStatus("evt_paid_a", "PROCESSED");
        awaitEventStatus("evt_paid_b", "PROCESSED");
        assertThat(fulfilments(c.orderId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM webhook_events")).isEqualTo(2);
    }

    /** "Multiple workers processing related events" + "conflicting state updates". */
    @Test
    void shuffledConcurrentEventsConvergeToTheNewestGatewayVersion() throws Exception {
        CheckoutResult c = checkout("worker-key-0006", AMOUNT);
        String gp = c.gatewayPaymentId();
        List<String> bodies = new ArrayList<>(List.of(
                eventJson("evt_s1", "payment.authorized", gp, 1, AMOUNT, "AUTHORIZED", 0),
                eventJson("evt_s2", "payment.captured", gp, 2, AMOUNT, "CAPTURED", 0),
                eventJson("evt_s3", "payment.refunded", gp, 3, AMOUNT, "PARTIALLY_REFUNDED", 100000),
                eventJson("evt_s4", "payment.refunded", gp, 4, AMOUNT, "REFUNDED", AMOUNT)));
        List<String> deliveries = new ArrayList<>();
        for (String b : bodies) {
            for (int i = 0; i < 5; i++) deliveries.add(b);   // each delivered 5 times
        }
        Collections.shuffle(deliveries, new java.util.Random(2026));

        var pool = Executors.newFixedThreadPool(20);
        List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
        for (String d : deliveries) {
            results.add(pool.submit(() -> deliverSigned(d)));
        }
        for (var r : results) assertThat(r.get()).isEqualTo(200);
        pool.shutdown();

        for (String id : List.of("evt_s1", "evt_s2", "evt_s3", "evt_s4")) awaitEventStatus(id, "PROCESSED");
        assertThat(paymentStatus(c.paymentId())).isEqualTo("REFUNDED:v4");
        assertThat(jdbc.sql("SELECT amount_refunded_minor FROM payments WHERE id = CAST(:id AS uuid)")
                .param("id", c.paymentId()).query(Long.class).single()).isEqualTo(AMOUNT);
        assertThat(fulfilments(c.orderId())).isEqualTo(1);
        assertThat(orderStatus(c.orderId())).isEqualTo("FULFILLED");
    }

    @Test
    void failedPaymentMarksOrderFailedAndShipsNothing() {
        CheckoutResult c = checkout("worker-key-0007", AMOUNT);
        gateway(c.gatewayPaymentId(), "fail");
        awaitOrderStatus(c.orderId(), "PAYMENT_FAILED");
        assertThat(fulfilments(c.orderId())).isZero();
    }

    @Test
    void impossibleTransitionIsDeadLetteredNotApplied() {
        CheckoutResult c = checkout("worker-key-0008", AMOUNT);
        String gp = c.gatewayPaymentId();
        deliverSigned(eventJson("evt_imp_cap", "payment.captured", gp, 2, AMOUNT, "CAPTURED", 0));
        awaitOrderStatus(c.orderId(), "FULFILLED");

        // Newer version, but CAPTURED -> FAILED is not reachable: a real anomaly, never auto-applied.
        deliverSigned(eventJson("evt_imp_fail", "payment.failed", gp, 3, AMOUNT, "FAILED", 0));
        awaitEventStatus("evt_imp_fail", "DEAD");

        assertThat(paymentStatus(c.paymentId())).isEqualTo("CAPTURED:v2");
        assertThat(attemptOutcomes("evt_imp_fail")).containsExactly("PERMANENT_FAILURE");  // no retries
        assertThat(jdbc.sql("SELECT last_error FROM webhook_events WHERE provider_event_id = 'evt_imp_fail'")
                .query(String.class).single()).contains("UNEXPECTED_TRANSITION");
        await().untilAsserted(() -> assertThat(deadLetters.brokerDlqDepth()).isEqualTo(1));
    }

    @Test
    void amountMismatchIsDeadLetteredImmediately() {
        CheckoutResult c = checkout("worker-key-0009", AMOUNT);
        deliverSigned(eventJson("evt_amt", "payment.captured", c.gatewayPaymentId(), 2, 1, "CAPTURED", 0));
        awaitEventStatus("evt_amt", "DEAD");
        assertThat(attemptOutcomes("evt_amt")).containsExactly("PERMANENT_FAILURE");
        assertThat(orderStatus(c.orderId())).isEqualTo("PAYMENT_PENDING");
        assertThat(count("SELECT count(*) FROM audit_logs WHERE action = 'DEAD_LETTERED'")).isEqualTo(1);
    }
}
