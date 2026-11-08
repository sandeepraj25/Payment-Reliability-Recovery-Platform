package com.payguard.reconciliation;

import com.payguard.faults.FaultInjector;
import com.payguard.faults.FaultPoint;
import com.payguard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/** Module G against the real stack: gateway simulator over HTTP, real DB, real worker. */
class ReconciliationIT extends IntegrationTestBase {

    private static final long AMOUNT = 499900;

    @Autowired
    private FaultInjector faults;

    private long runReconciliation() {
        return ((Number) admin().post("/api/reconciliation/runs").then().statusCode(200)
                .body("status", equalTo("COMPLETED")).extract().path("id")).longValue();
    }

    private List<String> mismatches(long runId, String resolution) {
        return jdbc.sql("SELECT mismatch_type FROM reconciliation_mismatches WHERE run_id = :r AND resolution = :res ORDER BY id")
                .param("r", runId).param("res", resolution).query(String.class).list();
    }

    /** Authorize -> webhook arrives. Capture -> webhook DROPPED. Merchant stuck at AUTHORIZED. */
    private CheckoutResult paidButWebhookLost(String key) {
        CheckoutResult c = checkout(key, AMOUNT);
        gateway(c.gatewayPaymentId(), "authorize");
        await().untilAsserted(() -> assertThat(jdbc.sql("SELECT status FROM payments WHERE id = CAST(:id AS uuid)")
                .param("id", c.paymentId()).query(String.class).single()).isEqualTo("AUTHORIZED"));
        faults.arm(FaultPoint.GATEWAY_DROP_WEBHOOK, 1, "test");
        gateway(c.gatewayPaymentId(), "capture");
        return c;
    }

    @Test
    void lostCaptureWebhookIsDetectedAndSafelyRecovered() throws Exception {
        CheckoutResult c = paidButWebhookLost("recon-key-0001");
        Thread.sleep(1500);
        assertThat(orderStatus(c.orderId())).isEqualTo("PAYMENT_PENDING");   // the customer paid, we did not ship

        long run = runReconciliation();

        assertThat(mismatches(run, "AUTO_RESOLVED")).containsExactly("GATEWAY_CAPTURED_MERCHANT_PENDING");
        assertThat(mismatches(run, "INFORMATIONAL")).contains("MISSING_WEBHOOK");
        assertThat(orderStatus(c.orderId())).isEqualTo("FULFILLED");
        assertThat(jdbc.sql("SELECT trigger_source FROM fulfilments").query(String.class).single()).isEqualTo("RECONCILIATION");
        assertThat(jdbc.sql("SELECT action FROM audit_logs WHERE action = 'RECONCILIATION_RECOVERY'").query(String.class).list()).hasSize(1);

        // Idempotent: a second run finds nothing left to fix and changes nothing.
        long second = runReconciliation();
        assertThat(mismatches(second, "AUTO_RESOLVED")).isEmpty();
        assertThat(count("SELECT count(*) FROM fulfilments")).isEqualTo(1);
    }

    @Test
    void recoveryRacingTheLateWebhookStillShipsOnce() throws Exception {
        CheckoutResult c = paidButWebhookLost("recon-key-0002");
        String capturedEvent = jdbc.sql("SELECT id FROM gateway.gateway_events WHERE payment_id = :p AND event_type = 'payment.captured'")
                .param("p", c.gatewayPaymentId()).query(String.class).single();

        var pool = Executors.newFixedThreadPool(2);
        List<Future<?>> f = new ArrayList<>();
        f.add(pool.submit(() -> admin().post("/api/reconciliation/runs").then().statusCode(200)));
        f.add(pool.submit(() -> admin().post("/sim/gateway/events/" + capturedEvent + "/redeliver").then().statusCode(200)));
        for (Future<?> x : f) x.get();
        pool.shutdown();

        awaitOrderStatus(c.orderId(), "FULFILLED");
        await().untilAsserted(() -> assertThat(eventStatus(capturedEvent)).isEqualTo("PROCESSED"));
        assertThat(count("SELECT count(*) FROM fulfilments")).isEqualTo(1);
    }

    @Test
    void merchantClaimingCaptureTheGatewayNeverMadeIsFlaggedAndNotOverwritten() {
        CheckoutResult c = checkout("recon-key-0003", AMOUNT);
        gateway(c.gatewayPaymentId(), "authorize");
        await().untilAsserted(() -> assertThat(jdbc.sql("SELECT gateway_version FROM payments WHERE id = CAST(:id AS uuid)")
                .param("id", c.paymentId()).query(Long.class).single()).isEqualTo(1L));
        // Corrupt merchant state (e.g. a bug, or a forged event in a system without HMAC).
        jdbc.sql("UPDATE payments SET status = 'CAPTURED' WHERE id = CAST(:id AS uuid)").param("id", c.paymentId()).update();

        long run = runReconciliation();

        assertThat(mismatches(run, "OPEN")).containsExactly("MERCHANT_CAPTURED_GATEWAY_NOT_CAPTURED");
        assertThat(jdbc.sql("SELECT status FROM payments WHERE id = CAST(:id AS uuid)").param("id", c.paymentId())
                .query(String.class).single()).isEqualTo("CAPTURED");   // ambiguous: left for a human, not "fixed"
    }

    @Test
    void amountMismatchIsNeverAutoRecoveredEvenWhenTheGatewayCaptured() {
        CheckoutResult c = paidButWebhookLost("recon-key-0004");
        jdbc.sql("UPDATE payments SET amount_minor = 100 WHERE id = CAST(:id AS uuid)").param("id", c.paymentId()).update();

        long run = runReconciliation();

        assertThat(mismatches(run, "OPEN")).containsExactly("AMOUNT_MISMATCH");
        assertThat(orderStatus(c.orderId())).isEqualTo("PAYMENT_PENDING");
        assertThat(count("SELECT count(*) FROM fulfilments")).isZero();
    }

    @Test
    void settlementOmissionAndDelayAreReportedSeparatelyFromCapture() throws Exception {
        CheckoutResult a = checkout("recon-key-0005", AMOUNT);
        CheckoutResult b = checkout("recon-key-0006", AMOUNT);
        for (CheckoutResult c : List.of(a, b)) {
            gateway(c.gatewayPaymentId(), "authorize");
            gateway(c.gatewayPaymentId(), "capture");
            awaitOrderStatus(c.orderId(), "FULFILLED");
        }
        faults.arm(FaultPoint.GATEWAY_SETTLEMENT_OMIT, 1, "test");
        admin().body("{}").post("/sim/gateway/settlements/run").then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].feeMinor", equalTo(9998))          // 2% of 499900 paise, integer maths
                .body("items[0].netMinor", equalTo(489902));

        long run = runReconciliation();
        assertThat(mismatches(run, "OPEN")).containsExactly("MISSING_SETTLEMENT");
        assertThat(count("SELECT count(*) FROM settlement_records")).isEqualTo(1);

        // Captured, then nothing for longer than expected (test: 5s) and no batch since -> DELAYED.
        CheckoutResult late = checkout("recon-key-0007", AMOUNT);
        gateway(late.gatewayPaymentId(), "authorize");
        gateway(late.gatewayPaymentId(), "capture");
        awaitOrderStatus(late.orderId(), "FULFILLED");
        Thread.sleep(6000);
        long run2 = runReconciliation();
        assertThat(mismatches(run2, "INFORMATIONAL")).contains("DELAYED_SETTLEMENT");
        // Re-importing the same settlement report changed nothing.
        assertThat(count("SELECT count(*) FROM settlement_records")).isEqualTo(1);
    }

    @Test
    void doubleChargeIsDetected() {
        CheckoutResult c = checkout("recon-key-0008", AMOUNT);
        // A second attempt for the SAME order also gets captured (e.g. customer paid in two tabs).
        String second = admin().header("Idempotency-Key", "second-attempt-0001")
                .body("{\"merchantOrderRef\":\"" + c.orderId() + "\",\"amountMinor\":" + AMOUNT + ",\"currency\":\"INR\"}")
                .post("/sim/gateway/payments").then().statusCode(201).extract().path("id");
        jdbc.sql("INSERT INTO payments (id, order_id, provider, gateway_payment_id, amount_minor, currency, status) VALUES (:id, CAST(:o AS uuid), 'payguard-sim', :g, :a, 'INR', 'CREATED')")
                .param("id", UUID.randomUUID()).param("o", c.orderId()).param("g", second).param("a", AMOUNT).update();
        for (String gp : List.of(c.gatewayPaymentId(), second)) {
            gateway(gp, "authorize");
            gateway(gp, "capture");
        }
        await().untilAsserted(() -> assertThat(count("SELECT count(*) FROM payments WHERE status = 'CAPTURED'")).isEqualTo(2));

        long run = runReconciliation();
        assertThat(mismatches(run, "OPEN")).contains("DOUBLE_CHARGE");
        assertThat(count("SELECT count(*) FROM fulfilments")).isEqualTo(1);   // shipped once, refund needed
    }

    @Test
    void deadLettersAndNoisyDuplicatesAreReported() {
        deliverSigned(eventJson("evt_recon_orphan", "payment.captured", "pay_unknown_x", 2, AMOUNT, "CAPTURED", 0));
        awaitEventStatus("evt_recon_orphan", "DEAD");
        String body = eventJson("evt_noisy", "payment.authorized", "pay_unknown_y", 1, AMOUNT, "AUTHORIZED", 0);
        for (int i = 0; i < 4; i++) deliverSigned(body);

        long run = runReconciliation();
        assertThat(mismatches(run, "OPEN")).contains("DEAD_LETTERED_EVENT");
        assertThat(mismatches(run, "INFORMATIONAL")).contains("DUPLICATE_DELIVERIES");
    }

    @Test
    void onlyOneRunAtATimeAndManualResolutionIsAudited() {
        CheckoutResult c = checkout("recon-key-0009", AMOUNT);
        gateway(c.gatewayPaymentId(), "authorize");
        await().untilAsserted(() -> assertThat(jdbc.sql("SELECT gateway_version FROM payments WHERE id = CAST(:id AS uuid)")
                .param("id", c.paymentId()).query(Long.class).single()).isEqualTo(1L));
        jdbc.sql("UPDATE payments SET status = 'CAPTURED' WHERE id = CAST(:id AS uuid)").param("id", c.paymentId()).update();

        // Simulate a run in progress: the partial unique index lets only one RUNNING row exist.
        jdbc.sql("INSERT INTO reconciliation_runs (trigger_type, triggered_by, status, window_from, window_to) VALUES ('MANUAL','other','RUNNING', now(), now())").update();
        admin().post("/api/reconciliation/runs").then().statusCode(409).body("code", equalTo("RECONCILIATION_ALREADY_RUNNING"));
        jdbc.sql("UPDATE reconciliation_runs SET status = 'ABANDONED' WHERE status = 'RUNNING'").update();

        long run = runReconciliation();
        long mismatchId = jdbc.sql("SELECT id FROM reconciliation_mismatches WHERE run_id = :r AND resolution = 'OPEN'")
                .param("r", run).query(Long.class).single();
        admin().body("{\"note\":\"\"}").post("/api/reconciliation/mismatches/" + mismatchId + "/resolve").then().statusCode(400);
        admin().body("{\"note\":\"verified with gateway support, ticket GW-7\"}")
                .post("/api/reconciliation/mismatches/" + mismatchId + "/resolve").then().statusCode(200);
        admin().body("{\"note\":\"again\"}").post("/api/reconciliation/mismatches/" + mismatchId + "/resolve")
                .then().statusCode(409);
        assertThat(count("SELECT count(*) FROM audit_logs WHERE action = 'MISMATCH_RESOLVED'")).isEqualTo(1);

        admin().get("/api/reconciliation/runs/" + run + "/report.csv").then().statusCode(200)
                .contentType(containsString("text/csv"))
                .body(containsString("MERCHANT_CAPTURED_GATEWAY_NOT_CAPTURED"));
    }
}
