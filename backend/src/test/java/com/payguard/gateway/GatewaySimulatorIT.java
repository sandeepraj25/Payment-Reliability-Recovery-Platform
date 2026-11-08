package com.payguard.gateway;

import com.payguard.support.IntegrationTestBase;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/** Module A: gateway simulator API, state machine enforcement and financial idempotency. */
class GatewaySimulatorIT extends IntegrationTestBase {

    private static final String BODY = "{\"merchantOrderRef\":\"order-1\",\"amountMinor\":499900,\"currency\":\"INR\"}";

    private String createPayment(String key) {
        return admin().header("Idempotency-Key", key).body(BODY)
                .when().post("/sim/gateway/payments")
                .then().statusCode(201).extract().path("id");
    }

    @Test
    void createIsIdempotentPerKey() {
        String id = createPayment("key-create-0001");

        admin().header("Idempotency-Key", "key-create-0001").body(BODY)
                .when().post("/sim/gateway/payments")
                .then().statusCode(200)
                .header("Idempotent-Replayed", "true")
                .body("id", equalTo(id));

        assertThat(count("SELECT count(*) FROM gateway.gateway_payments")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentRequestIs422() {
        createPayment("key-create-0002");
        admin().header("Idempotency-Key", "key-create-0002")
                .body("{\"merchantOrderRef\":\"order-1\",\"amountMinor\":1,\"currency\":\"INR\"}")
                .when().post("/sim/gateway/payments")
                .then().statusCode(422).body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void fractionalAmountIsRejected() {
        admin().header("Idempotency-Key", "key-create-0003")
                .body("{\"merchantOrderRef\":\"order-1\",\"amountMinor\":4999.5,\"currency\":\"INR\"}")
                .when().post("/sim/gateway/payments")
                .then().statusCode(400);
        assertThat(count("SELECT count(*) FROM gateway.gateway_payments")).isZero();
    }

    @Test
    void invalidTransitionIsRejected() {
        String id = createPayment("key-create-0004");
        admin().when().post("/sim/gateway/payments/" + id + "/capture")
                .then().statusCode(409).body("code", equalTo("INVALID_STATE_TRANSITION"));
        admin().when().get("/sim/gateway/payments/" + id).then().body("status", equalTo("CREATED"));
    }

    @Test
    void eachTransitionEmitsExactlyOneVersionedEvent() {
        String id = createPayment("key-create-0005");
        admin().post("/sim/gateway/payments/" + id + "/authorize").then().statusCode(200).body("version", equalTo(1));
        admin().post("/sim/gateway/payments/" + id + "/capture").then().statusCode(200).body("version", equalTo(2));
        // Retried capture: NO_OP, same version, no new event.
        admin().post("/sim/gateway/payments/" + id + "/capture").then().statusCode(200).body("version", equalTo(2));

        List<String> types = jdbc.sql("SELECT event_type FROM gateway.gateway_events WHERE payment_id = :id ORDER BY payment_version")
                .param("id", id).query(String.class).list();
        assertThat(types).containsExactly("payment.authorized", "payment.captured");
    }

    @Test
    void concurrentCapturesProduceOneCaptureEvent() throws Exception {
        String id = createPayment("key-create-0006");
        admin().post("/sim/gateway/payments/" + id + "/authorize").then().statusCode(200);

        List<Integer> statuses = runConcurrently(50, () ->
                admin().post("/sim/gateway/payments/" + id + "/capture").statusCode());

        assertThat(statuses).allMatch(s -> s == 200); // row lock serialises them: 1 APPLY + 49 NO_OP
        assertThat(count("SELECT count(*) FROM gateway.gateway_events WHERE event_type = 'payment.captured'")).isEqualTo(1);
    }

    @Test
    void refundIsIdempotentAndCannotExceedTheCapturedAmount() {
        String id = capturedPayment("key-create-0007");

        String refundBody = "{\"amountMinor\":100000}";
        admin().header("Idempotency-Key", "refund-key-0001").body(refundBody)
                .post("/sim/gateway/payments/" + id + "/refunds").then().statusCode(201);
        admin().header("Idempotency-Key", "refund-key-0001").body(refundBody)
                .post("/sim/gateway/payments/" + id + "/refunds").then().statusCode(200).header("Idempotent-Replayed", "true");

        admin().header("Idempotency-Key", "refund-key-0002").body("{\"amountMinor\":400000}")
                .post("/sim/gateway/payments/" + id + "/refunds").then().statusCode(422)
                .body("code", equalTo("REFUND_EXCEEDS_REMAINING"));

        admin().get("/sim/gateway/payments/" + id).then()
                .body("status", equalTo("PARTIALLY_REFUNDED"))
                .body("amountRefundedMinor", equalTo(100000));
        assertThat(count("SELECT count(*) FROM gateway.gateway_refunds")).isEqualTo(1);
    }

    @Test
    void concurrentRefundsWithDifferentKeysCannotOverRefund() throws Exception {
        String id = capturedPayment("key-create-0008");

        // 10 refunds of 60% each, all at once. Only one can fit.
        List<Integer> statuses = runConcurrently(10, new Callable<Integer>() {
            private final java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            @Override
            public Integer call() {
                return admin().header("Idempotency-Key", "refund-conc-" + String.format("%04d", n.incrementAndGet()))
                        .body("{\"amountMinor\":299940}")
                        .post("/sim/gateway/payments/" + id + "/refunds").statusCode();
            }
        });

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(count("SELECT amount_refunded_minor FROM gateway.gateway_payments")).isEqualTo(299940);
    }

    @Test
    void transitionsSendSignedWebhooksThatTheMerchantStores() {
        String id = capturedPayment("key-create-0009");

        // The delivery log row is written AFTER the merchant answers, so wait for both.
        await().untilAsserted(() -> {
            assertThat(count("SELECT count(*) FROM webhook_events WHERE gateway_payment_id = '" + id + "'")).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM gateway.webhook_deliveries WHERE http_status = 200")).isEqualTo(2);
        });

        // Manual redelivery = duplicate delivery, on demand.
        String capturedEventId = jdbc.sql("SELECT id FROM gateway.gateway_events WHERE payment_id = :id AND event_type = 'payment.captured'")
                .param("id", id).query(String.class).single();
        Response r = admin().post("/sim/gateway/events/" + capturedEventId + "/redeliver");
        r.then().statusCode(200).body("httpStatus", equalTo(200));

        assertThat(count("SELECT count(*) FROM webhook_events WHERE gateway_payment_id = '" + id + "'")).isEqualTo(2);
        assertThat(count("SELECT duplicate_count FROM webhook_events WHERE provider_event_id = '" + capturedEventId + "'")).isEqualTo(1);
    }

    @Test
    void gatewayApiRequiresCredentials() {
        io.restassured.RestAssured.given().contentType("application/json").header("Idempotency-Key", "key-noauth-01")
                .body(BODY).post("/sim/gateway/payments").then().statusCode(401);
    }

    // ------------------------------------------------------------------ helpers

    private String capturedPayment(String key) {
        String id = createPayment(key);
        admin().post("/sim/gateway/payments/" + id + "/authorize").then().statusCode(200);
        admin().post("/sim/gateway/payments/" + id + "/capture").then().statusCode(200);
        return id;
    }

    /** Starts n tasks and releases them at the same instant with a latch, to maximise overlap. */
    private static <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }
}
