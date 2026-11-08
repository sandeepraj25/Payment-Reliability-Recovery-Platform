package com.payguard.worker;

import com.payguard.faults.FaultInjector;
import com.payguard.faults.FaultPoint;
import com.payguard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * Failure tests: each one makes REAL code fail at a specific line (fault injection) and checks that the
 * real recovery mechanism restores the invariants. Nothing is mocked.
 */
class FailureRecoveryIT extends IntegrationTestBase {

    private static final long AMOUNT = 499900;

    @Autowired
    private FaultInjector faults;

    @Autowired
    private DeadLetterService deadLetters;

    private long fulfilments() {
        return count("SELECT count(*) FROM fulfilments");
    }

    @Test
    void workerCrashBeforeCommitLeavesNoTraceAndTheRedeliveryProcessesIt() {
        CheckoutResult c = checkout("fail-key-0001", AMOUNT);
        faults.arm(FaultPoint.WORKER_CRASH_BEFORE_COMMIT, 1, "test");

        deliverSigned(eventJson("evt_crash_pre", "payment.captured", c.gatewayPaymentId(), 2, AMOUNT, "CAPTURED", 0));
        awaitOrderStatus(c.orderId(), "FULFILLED");

        assertThat(fulfilments()).isEqualTo(1);
        // The crashed attempt rolled back COMPLETELY - even its own attempt row. Only the successful
        // redelivery is recorded, and RabbitMQ flagged it as a redelivery.
        assertThat(attemptOutcomes("evt_crash_pre")).containsExactly("SUCCESS");
        assertThat(jdbc.sql("SELECT redelivered FROM processing_attempts").query(Boolean.class).single()).isTrue();
    }

    @Test
    void workerCrashAfterCommitBeforeAckIsHarmless() {
        CheckoutResult c = checkout("fail-key-0002", AMOUNT);
        faults.arm(FaultPoint.WORKER_CRASH_AFTER_COMMIT, 1, "test");

        deliverSigned(eventJson("evt_crash_post", "payment.captured", c.gatewayPaymentId(), 2, AMOUNT, "CAPTURED", 0));
        awaitOrderStatus(c.orderId(), "FULFILLED");
        await().untilAsserted(() -> assertThat(attemptOutcomes("evt_crash_post"))
                .containsExactly("SUCCESS", "SKIPPED_ALREADY_PROCESSED"));

        assertThat(fulfilments()).isEqualTo(1);   // the redelivered message did NOT ship again
    }

    @Test
    void transientFailuresAreRetriedWithExponentialBackoffAndJitter() {
        CheckoutResult c = checkout("fail-key-0003", AMOUNT);
        faults.arm(FaultPoint.WORKER_TRANSIENT_ERROR, 2, "test");

        deliverSigned(eventJson("evt_retry", "payment.captured", c.gatewayPaymentId(), 2, AMOUNT, "CAPTURED", 0));
        awaitOrderStatus(c.orderId(), "FULFILLED");

        assertThat(attemptOutcomes("evt_retry")).containsExactly("TRANSIENT_FAILURE", "TRANSIENT_FAILURE", "SUCCESS");
        List<Long> delays = jdbc.sql("SELECT retry_delay_ms FROM processing_attempts WHERE outcome = 'TRANSIENT_FAILURE' ORDER BY attempt_no")
                .query(Long.class).list();
        // test profile: base 200ms -> attempt 1 in [100,200], attempt 2 in [200,400]
        assertThat(delays.get(0)).isBetween(100L, 200L);
        assertThat(delays.get(1)).isBetween(200L, 400L);
        assertThat(jdbc.sql("SELECT purpose FROM outbox_events ORDER BY id").query(String.class).list())
                .containsExactly("INGEST", "RETRY", "RETRY");
        assertThat(fulfilments()).isEqualTo(1);
    }

    @Test
    void exhaustedRetriesAreDeadLetteredAndASafeAuditedReplayRecoversThem() {
        // A webhook for a gateway payment the merchant does not know (yet): PAYMENT_NOT_FOUND is transient.
        String orphan = "pay_orphan_" + UUID.randomUUID().toString().substring(0, 8);
        deliverSigned(eventJson("evt_orphan", "payment.captured", orphan, 2, AMOUNT, "CAPTURED", 0));

        awaitEventStatus("evt_orphan", "DEAD");
        assertThat(attemptOutcomes("evt_orphan"))
                .containsExactly("TRANSIENT_FAILURE", "TRANSIENT_FAILURE", "TRANSIENT_FAILURE"); // max-attempts=3, then stop
        await().untilAsserted(() -> assertThat(deadLetters.brokerDlqDepth()).isEqualTo(1));
        admin().get("/api/dead-letters").then().statusCode(200).body("events[0].providerEventId", equalTo("evt_orphan"));

        // The missing link appears (e.g. ops fixed the data). Now a replay can succeed.
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        jdbc.sql("INSERT INTO orders (id, customer_ref, amount_minor, currency, status) VALUES (:id, 'cust', :a, 'INR', 'PAYMENT_PENDING')")
                .param("id", orderId).param("a", AMOUNT).update();
        jdbc.sql("INSERT INTO payments (id, order_id, provider, gateway_payment_id, amount_minor, currency, status) VALUES (:id, :o, 'payguard-sim', :g, :a, 'INR', 'CREATED')")
                .param("id", paymentId).param("o", orderId).param("g", orphan).param("a", AMOUNT).update();

        long eventId = jdbc.sql("SELECT id FROM webhook_events WHERE provider_event_id = 'evt_orphan'").query(Long.class).single();
        admin().body("{}").post("/api/dead-letters/" + eventId + "/replay").then().statusCode(400);  // reason required
        admin().body("{\"reason\":\"payment link repaired by ops ticket OPS-42\"}")
                .post("/api/dead-letters/" + eventId + "/replay").then().statusCode(200);

        awaitOrderStatus(orderId.toString(), "FULFILLED");
        assertThat(attemptOutcomes("evt_orphan")).endsWith("SUCCESS");
        assertThat(jdbc.sql("SELECT action FROM audit_logs ORDER BY id").query(String.class).list())
                .containsExactly("DEAD_LETTERED", "DEAD_LETTER_REPLAYED");
        assertThat(jdbc.sql("SELECT actor FROM audit_logs WHERE action = 'DEAD_LETTER_REPLAYED'").query(String.class).single())
                .isEqualTo(ADMIN_USER);

        // Replaying something that is no longer DEAD is refused (no blind re-processing).
        admin().body("{\"reason\":\"again\"}").post("/api/dead-letters/" + eventId + "/replay")
                .then().statusCode(409).body("code", equalTo("NOT_DEAD_LETTERED"));
        assertThat(fulfilments()).isEqualTo(1);
    }

    @Test
    void outboxPublisherCrashAfterBrokerConfirmRepublishesAndTheWorkerDeduplicates() {
        CheckoutResult c = checkout("fail-key-0005", AMOUNT);
        faults.arm(FaultPoint.OUTBOX_CRASH_AFTER_CONFIRM, 1, "test");

        deliverSigned(eventJson("evt_pub_crash", "payment.captured", c.gatewayPaymentId(), 2, AMOUNT, "CAPTURED", 0));
        awaitOrderStatus(c.orderId(), "FULFILLED");
        await().untilAsserted(() -> assertThat(attemptOutcomes("evt_pub_crash"))
                .containsExactlyInAnyOrder("SUCCESS", "SKIPPED_ALREADY_PROCESSED"));

        assertThat(jdbc.sql("SELECT status FROM outbox_events").query(String.class).single()).isEqualTo("PUBLISHED");
        assertThat(fulfilments()).isEqualTo(1);
    }

    @Test
    void brokerUnavailableKeepsOutboxRowsPendingWithBackoffUntilItReturns() {
        CheckoutResult c = checkout("fail-key-0006", AMOUNT);
        faults.arm(FaultPoint.OUTBOX_BROKER_UNAVAILABLE, 3, "test");

        // The webhook is acknowledged even though the broker is "down": durability comes from PostgreSQL.
        assertThat(deliverSigned(eventJson("evt_broker", "payment.captured", c.gatewayPaymentId(), 2, AMOUNT, "CAPTURED", 0)))
                .isEqualTo(200);
        awaitOrderStatus(c.orderId(), "FULFILLED");

        var row = jdbc.sql("SELECT publish_attempts, last_error FROM outbox_events")
                .query((rs, n) -> new Object[]{rs.getInt(1), rs.getString(2)}).single();
        assertThat((Integer) row[0]).isEqualTo(4);                       // 3 failed + 1 successful
        assertThat((String) row[1]).contains("broker unreachable");
    }

    @Test
    void webhookIsNotAcknowledgedWhenItCannotBeStored() {
        CheckoutResult c = checkout("fail-key-0007", AMOUNT);
        faults.arm(FaultPoint.INGESTION_DB_UNAVAILABLE, 1, "test");
        String body = eventJson("evt_db_down", "payment.captured", c.gatewayPaymentId(), 2, AMOUNT, "CAPTURED", 0);

        assertThat(deliverSigned(body)).isEqualTo(503);           // gateway sees failure -> will retry
        assertThat(eventStatus("evt_db_down")).isEqualTo("MISSING");

        assertThat(deliverSigned(body)).isEqualTo(200);           // the gateway's retry
        awaitOrderStatus(c.orderId(), "FULFILLED");
    }
}
