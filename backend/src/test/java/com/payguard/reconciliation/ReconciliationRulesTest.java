package com.payguard.reconciliation;

import com.payguard.domain.payment.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.payguard.domain.payment.PaymentStatus.*;
import static com.payguard.reconciliation.MismatchType.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Every reconciliation rule, tested as a pure function (no DB, no HTTP). */
class ReconciliationRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final ReconciliationRules.Settings SETTINGS =
            new ReconciliationRules.Settings(Duration.ofHours(48), Duration.ofMinutes(15));
    private static final UUID ORDER = UUID.randomUUID();

    private static ReconciliationRules.MerchantPayment m(String gp, PaymentStatus s, long v, long maxReceived) {
        return new ReconciliationRules.MerchantPayment(UUID.randomUUID(), ORDER, gp, 499900, 0, "INR", s, v, maxReceived, NOW);
    }

    private static ReconciliationRules.GatewayPayment g(String id, PaymentStatus s, long v) {
        return new ReconciliationRules.GatewayPayment(id, ORDER.toString(), 499900, 0, "INR", s, v,
                s == CAPTURED ? NOW.minusSeconds(60) : null);
    }

    private static List<MismatchType> types(List<ReconciliationRules.Finding> f) {
        return f.stream().map(ReconciliationRules.Finding::type).toList();
    }

    private static List<ReconciliationRules.Finding> run(List<ReconciliationRules.MerchantPayment> merchant,
                                                         List<ReconciliationRules.GatewayPayment> gateway) {
        return ReconciliationRules.compare(merchant, gateway, Map.of(), null, NOW, SETTINGS);
    }

    @Test
    void consistentPaymentProducesNoFinding() {
        assertThat(run(List.of(m("pay_1", CAPTURED, 2, 2)), List.of(g("pay_1", CAPTURED, 2)))).isEmpty();
    }

    @Test
    void lostCaptureWebhookIsDetectedAsCapturedButPendingPlusMissingWebhook() {
        var f = run(List.of(m("pay_1", AUTHORIZED, 1, 1)), List.of(g("pay_1", CAPTURED, 2)));
        assertThat(types(f)).containsExactly(GATEWAY_CAPTURED_MERCHANT_PENDING, MISSING_WEBHOOK);
        assertThat(f.get(0).type().handling()).isEqualTo(MismatchType.Handling.AUTO_RECOVER);
        assertThat(f.get(1).evidence()).containsEntry("missingVersions", "2..2");
    }

    @Test
    void webhookReceivedButNotYetAppliedIsLagWithoutMissingWebhook() {
        // v2 arrived (maxReceived=2) but the worker has not applied it yet: lag, not a lost webhook.
        assertThat(types(run(List.of(m("pay_1", AUTHORIZED, 1, 2)), List.of(g("pay_1", CAPTURED, 2)))))
                .containsExactly(GATEWAY_CAPTURED_MERCHANT_PENDING);
    }

    @Test
    void nonPaidLagIsStateLag() {
        assertThat(types(run(List.of(m("pay_1", CREATED, 0, 0)), List.of(g("pay_1", FAILED, 1)))))
                .containsExactly(STATE_LAG, MISSING_WEBHOOK);
    }

    @Test
    void merchantCapturedButGatewayNotIsNeverAutoFixed() {
        var f = run(List.of(m("pay_1", CAPTURED, 2, 2)), List.of(g("pay_1", AUTHORIZED, 2)));
        assertThat(types(f)).containsExactly(MERCHANT_CAPTURED_GATEWAY_NOT_CAPTURED);
        assertThat(f.get(0).type().handling()).isEqualTo(MismatchType.Handling.MANUAL_REVIEW);
    }

    @Test
    void merchantAheadOfGateway() {
        assertThat(types(run(List.of(m("pay_1", AUTHORIZED, 3, 3)), List.of(g("pay_1", AUTHORIZED, 1)))))
                .containsExactly(MERCHANT_AHEAD_OF_GATEWAY);
    }

    @Test
    void unreachableGatewayStateIsUnexpected() {
        assertThat(types(run(List.of(m("pay_1", FAILED, 1, 1)), List.of(g("pay_1", CAPTURED, 2)))))
                .containsExactly(UNEXPECTED_STATE);
    }

    @Test
    void amountMismatchWinsOverEverythingElse() {
        var gw = new ReconciliationRules.GatewayPayment("pay_1", ORDER.toString(), 1, 0, "INR", CAPTURED, 2, NOW);
        assertThat(types(run(List.of(m("pay_1", AUTHORIZED, 1, 1)), List.of(gw)))).containsExactly(AMOUNT_MISMATCH);
    }

    @Test
    void orphansInBothDirections() {
        var f = run(List.of(m("pay_gone", CREATED, 0, 0)), List.of(g("pay_orphan", AUTHORIZED, 1)));
        assertThat(types(f)).containsExactlyInAnyOrder(GATEWAY_PAYMENT_NOT_FOUND, ORPHAN_GATEWAY_PAYMENT);
    }

    @Test
    void twoPaidPaymentsForOneOrderIsADoubleCharge() {
        var f = run(List.of(m("pay_a", CAPTURED, 2, 2), m("pay_b", CAPTURED, 2, 2)),
                List.of(g("pay_a", CAPTURED, 2), g("pay_b", CAPTURED, 2)));
        assertThat(types(f)).containsExactly(DOUBLE_CHARGE);
        assertThat(f.get(0).orderId()).isEqualTo(ORDER);
    }

    @Test
    void settlementRules() {
        var merchant = List.of(m("pay_1", CAPTURED, 2, 2));
        var gateway = List.of(g("pay_1", CAPTURED, 2));   // captured 60s before NOW

        // A batch with a cutoff AFTER the capture exists, but the payment is not in it -> missing.
        assertThat(types(ReconciliationRules.compare(merchant, gateway, Map.of(), NOW, NOW, SETTINGS)))
                .containsExactly(MISSING_SETTLEMENT);
        // No batch covers it yet, and it is younger than the expected settlement time -> nothing.
        assertThat(ReconciliationRules.compare(merchant, gateway, Map.of(), NOW.minusSeconds(3600), NOW, SETTINGS)).isEmpty();
        // Older than expected and still not settled -> delayed.
        var shortSla = new ReconciliationRules.Settings(Duration.ofSeconds(10), Duration.ofMinutes(15));
        assertThat(types(ReconciliationRules.compare(merchant, gateway, Map.of(), null, NOW, shortSla)))
                .containsExactly(DELAYED_SETTLEMENT);
        // Settled for more than was captured -> mismatch.
        var bad = Map.of("pay_1", new ReconciliationRules.Settlement("stl_1", 500000, "INR"));
        assertThat(types(ReconciliationRules.compare(merchant, gateway, bad, NOW, NOW, SETTINGS)))
                .containsExactly(SETTLEMENT_AMOUNT_MISMATCH);
        // Settled correctly -> nothing.
        var ok = Map.of("pay_1", new ReconciliationRules.Settlement("stl_1", 499900, "INR"));
        assertThat(ReconciliationRules.compare(merchant, gateway, ok, NOW, NOW, SETTINGS)).isEmpty();
    }

    @Test
    void capturedIsNotSettled() {
        // Same payment, CAPTURED on both sides: the payment comparison is clean, settlement is judged separately.
        var f = ReconciliationRules.compare(List.of(m("pay_1", CAPTURED, 2, 2)), List.of(g("pay_1", CAPTURED, 2)),
                Map.of(), NOW, NOW, SETTINGS);
        assertThat(types(f)).containsExactly(MISSING_SETTLEMENT);
    }
}
