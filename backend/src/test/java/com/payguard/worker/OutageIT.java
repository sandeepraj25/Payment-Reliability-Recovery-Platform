package com.payguard.worker;

import com.payguard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * REAL outages: the Docker container of RabbitMQ / PostgreSQL is frozen (docker pause) while the app runs.
 * Only possible when the tests own the containers (Testcontainers), so skipped with external services.
 */
class OutageIT extends IntegrationTestBase {

    private static final long AMOUNT = 499900;

    @Test
    void rabbitMqOutageDelaysProcessingButLosesNothing() throws Exception {
        assumeTrue(RABBIT != null, "needs Testcontainers RabbitMQ");
        CheckoutResult c = checkout("outage-key-0001", AMOUNT);

        pause(RABBIT);
        try {
            // Ingestion does not depend on the broker at all.
            assertThat(deliverSigned(eventJson("evt_mq_down", "payment.captured", c.gatewayPaymentId(), 2, AMOUNT, "CAPTURED", 0)))
                    .isEqualTo(200);
            Thread.sleep(5000);  // > confirm timeout (3s in tests)
            assertThat(orderStatus(c.orderId())).isEqualTo("PAYMENT_PENDING");
            assertThat(jdbc.sql("SELECT status FROM outbox_events").query(String.class).single()).isEqualTo("PENDING");
        } finally {
            unpause(RABBIT);
        }

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(orderStatus(c.orderId())).isEqualTo("FULFILLED"));
        assertThat(count("SELECT publish_attempts FROM outbox_events")).isGreaterThanOrEqualTo(2);
        assertThat(count("SELECT count(*) FROM fulfilments")).isEqualTo(1);
    }

    @Test
    void databaseOutageNeverProducesAFalseAcknowledgement() {
        assumeTrue(POSTGRES != null, "needs Testcontainers PostgreSQL");
        CheckoutResult c = checkout("outage-key-0002", AMOUNT);
        String body = eventJson("evt_pg_down", "payment.captured", c.gatewayPaymentId(), 2, AMOUNT, "CAPTURED", 0);

        pause(POSTGRES);
        int statusDuringOutage;
        try {
            statusDuringOutage = deliverSigned(body);
        } finally {
            unpause(POSTGRES);
        }
        // The invariant: no 2xx unless durably stored. (503 or 500 depending on where the failure surfaced.)
        assertThat(statusDuringOutage).isGreaterThanOrEqualTo(500);

        // The gateway retries later; now it works and is processed exactly once.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(deliverSigned(body)).isEqualTo(200));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(orderStatus(c.orderId())).isEqualTo("FULFILLED"));
        assertThat(count("SELECT count(*) FROM fulfilments")).isEqualTo(1);
    }
}
