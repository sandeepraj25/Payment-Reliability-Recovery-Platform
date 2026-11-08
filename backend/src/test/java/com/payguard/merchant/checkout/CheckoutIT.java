package com.payguard.merchant.checkout;

import com.payguard.common.crypto.RequestFingerprint;
import com.payguard.support.IntegrationTestBase;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/** Module C part 1: API request idempotency, including crash recovery. */
class CheckoutIT extends IntegrationTestBase {

    private static final String BODY = "{\"customerRef\":\"cust_1\",\"amountMinor\":499900,\"currency\":\"INR\"}";

    private Response checkout(String key, String body) {
        return admin().header("Idempotency-Key", key).body(body).when().post("/api/checkout");
    }

    @Test
    void checkoutCreatesOrderPaymentAndGatewayPayment() {
        Response r = checkout("checkout-key-0001", BODY);
        r.then().statusCode(201)
                .header("Idempotent-Replayed", "false")
                .body("gatewayPaymentId", startsWith("pay_"))
                .body("amountMinor", equalTo(499900))
                .body("orderStatus", equalTo("PAYMENT_PENDING"));

        String paymentId = r.path("paymentId");
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments WHERE gateway_payment_id IS NOT NULL")).isEqualTo(1);
        // The gateway key is derived from OUR payment id - the property that makes retries safe.
        assertThat(jdbc.sql("SELECT create_idempotency_key FROM gateway.gateway_payments").query(String.class).single())
                .isEqualTo("pgk_" + paymentId);
        assertThat(jdbc.sql("SELECT status FROM idempotency_records").query(String.class).single()).isEqualTo("COMPLETED");
    }

    @Test
    void retryWithSameKeyReturnsTheIdenticalResponse() {
        String first = checkout("checkout-key-0002", BODY).then().statusCode(201).extract().asString();
        Response second = checkout("checkout-key-0002", BODY);

        second.then().statusCode(201).header("Idempotent-Replayed", "true");
        assertThat(second.asString()).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM gateway.gateway_payments")).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentBodyIsRejected() {
        checkout("checkout-key-0003", BODY).then().statusCode(201);
        checkout("checkout-key-0003", BODY.replace("499900", "100"))
                .then().statusCode(422).body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
    }

    @Test
    void differentKeysAreDifferentOrders() {
        checkout("checkout-key-0004", BODY).then().statusCode(201);
        checkout("checkout-key-0005", BODY).then().statusCode(201);
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM gateway.gateway_payments")).isEqualTo(2);
    }

    @Test
    void hundredConcurrentRetriesCreateOneOrder() throws Exception {
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return checkout("checkout-key-conc1", BODY);
            }));
        }
        start.countDown();

        List<String> successBodies = new ArrayList<>();
        for (Future<Response> f : futures) {
            Response r = f.get();
            // 201 = winner or replay of the stored response; 409 = winner still in progress at that moment;
            // 503 GATEWAY_UNAVAILABLE = the winner's call to the gateway timed out (outcome unknown).
            // That 503 is NOT a bug: on a busy CI machine 100 simultaneous requests (each paying a BCrypt
            // check) can push the internal gateway call past its 2 s test read-timeout. The API contract says
            // "retry with the same key", so the test does exactly that below. (This assertion used to allow
            // only 201/409 and failed intermittently in CI run 42468105642.)
            assertThat(r.statusCode()).isIn(201, 409, 503);
            if (r.statusCode() == 503) {
                assertThat(r.<String>path("code")).isEqualTo("GATEWAY_UNAVAILABLE");
            }
            if (r.statusCode() == 201) {
                successBodies.add(r.asString());
            }
        }
        pool.shutdown();

        // Behave like a real client: keep retrying with the SAME key until the request completes.
        Response last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            last = checkout("checkout-key-conc1", BODY);
            if (last.statusCode() == 201) {
                break;
            }
            assertThat(last.statusCode()).isIn(409, 503);
            Thread.sleep(250);
        }
        assertThat(last.statusCode()).as("request eventually completes").isEqualTo(201);
        String finalBody = last.asString();

        // The correctness guarantees, independent of timing: one stored response, one order, one charge.
        assertThat(successBodies).allMatch(b -> b.equals(finalBody));
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM gateway.gateway_payments")).isEqualTo(1);
    }

    /**
     * The hardest case: the server crashed AFTER the gateway created the payment but BEFORE we
     * stored the result. We rebuild exactly that database state, then let the client retry.
     */
    @Test
    void retryAfterCrashResumesAndDoesNotCreateASecondGatewayPayment() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.sql("INSERT INTO orders (id, customer_ref, amount_minor, currency, status) VALUES (:id, 'cust_1', 499900, 'INR', 'PAYMENT_PENDING')")
                .param("id", orderId).update();
        jdbc.sql("INSERT INTO payments (id, order_id, provider, amount_minor, currency, status) VALUES (:id, :orderId, 'payguard-sim', 499900, 'INR', 'CREATED')")
                .param("id", paymentId).param("orderId", orderId).update();
        jdbc.sql("""
                INSERT INTO idempotency_records (scope, idempotency_key, request_fingerprint, status, recovery_point,
                                                 resource_id, lease_token, locked_until, expires_at)
                VALUES (:scope, 'checkout-key-crash', :fp, 'IN_PROGRESS', 'ORDER_CREATED', :rid, :token, :lockedUntil, :expires)
                """)
                .param("scope", CheckoutService.SCOPE)
                .param("fp", RequestFingerprint.of("cust_1", "499900", "INR"))
                .param("rid", paymentId.toString())
                .param("token", UUID.randomUUID())
                .param("lockedUntil", Timestamp.from(now.minus(1, ChronoUnit.MINUTES)))   // crashed owner's lease expired
                .param("expires", Timestamp.from(now.plus(1, ChronoUnit.DAYS)))
                .update();
        // The gateway call that "succeeded just before the crash":
        String gatewayPaymentId = admin().header("Idempotency-Key", CheckoutService.gatewayKeyFor(paymentId))
                .body("{\"merchantOrderRef\":\"" + orderId + "\",\"amountMinor\":499900,\"currency\":\"INR\"}")
                .post("/sim/gateway/payments").then().statusCode(201).extract().path("id");

        checkout("checkout-key-crash", BODY).then().statusCode(201)
                .body("orderId", equalTo(orderId.toString()))
                .body("paymentId", equalTo(paymentId.toString()))
                .body("gatewayPaymentId", equalTo(gatewayPaymentId));

        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM gateway.gateway_payments")).isEqualTo(1);
    }

    @Test
    void requestStillInProgressGets409() {
        jdbc.sql("""
                INSERT INTO idempotency_records (scope, idempotency_key, request_fingerprint, status, lease_token, locked_until, expires_at)
                VALUES (:scope, 'checkout-key-busy', :fp, 'IN_PROGRESS', :token, now() + interval '30 seconds', now() + interval '1 day')
                """)
                .param("scope", CheckoutService.SCOPE)
                .param("fp", RequestFingerprint.of("cust_1", "499900", "INR"))
                .param("token", UUID.randomUUID())
                .update();

        checkout("checkout-key-busy", BODY).then().statusCode(409).body("code", equalTo("IDEMPOTENCY_REQUEST_IN_PROGRESS"));
        assertThat(count("SELECT count(*) FROM orders")).isZero();
    }

    @Test
    void invalidRequestsAreRejected() {
        checkout("checkout-key-0006", BODY.replace("499900", "4999.5")).then().statusCode(400);
        checkout("short", BODY).then().statusCode(400);
        admin().body(BODY).post("/api/checkout").then().statusCode(400).body("code", equalTo("MISSING_HEADER"));
        checkout("checkout-key-0007", BODY.replace("INR", "inr")).then().statusCode(400).body("code", equalTo("VALIDATION_FAILED"));
        assertThat(count("SELECT count(*) FROM orders")).isZero();
    }

    @Test
    void frameworkErrorsKeepTheirRealStatusInsteadOf500() {
        admin().get("/api/orders/not-a-uuid").then().statusCode(400);
        admin().get("/api/orders/" + UUID.randomUUID()).then().statusCode(404).body("code", equalTo("NOT_FOUND"));
        admin().get("/api/no-such-endpoint").then().statusCode(404);
        admin().delete("/api/checkout").then().statusCode(405);
    }

    @Test
    void orderCanBeReadBack() {
        String orderId = checkout("checkout-key-0008", BODY).then().statusCode(201).extract().path("orderId");
        admin().get("/api/orders/" + orderId).then().statusCode(200)
                .body("status", equalTo("PAYMENT_PENDING"))
                .body("payments[0].status", equalTo("CREATED"))
                .body("payments[0].gatewayPaymentId", startsWith("pay_"));
    }
}
