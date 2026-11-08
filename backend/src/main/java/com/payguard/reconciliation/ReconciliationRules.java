package com.payguard.reconciliation;

import com.payguard.domain.payment.PaymentStateMachine;
import com.payguard.domain.payment.PaymentStatus;
import com.payguard.merchant.GatewayStateApplier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure comparison logic: data in, findings out. No database, no HTTP, no clock - so every rule is
 * unit-tested directly (ReconciliationRulesTest). The service decides what to do with the findings.
 */
public final class ReconciliationRules {

    private ReconciliationRules() {
    }

    /** One merchant payment row plus the highest gateway version it ever received as a webhook. */
    public record MerchantPayment(UUID id, UUID orderId, String gatewayPaymentId, long amountMinor,
                                  long amountRefundedMinor, String currency, PaymentStatus status,
                                  long gatewayVersion, long maxReceivedWebhookVersion, Instant createdAt) {
    }

    public record GatewayPayment(String id, String merchantOrderRef, long amountMinor, long amountRefundedMinor,
                                 String currency, PaymentStatus status, long version, Instant capturedAt) {
    }

    public record Settlement(String batchId, long grossMinor, String currency) {
    }

    public record Finding(MismatchType type, String gatewayPaymentId, UUID merchantPaymentId, UUID orderId,
                          String merchantValue, String gatewayValue, Map<String, Object> evidence) {
    }

    public record Settings(Duration settlementExpectedWithin, Duration unlinkedGracePeriod) {
    }

    public static List<Finding> compare(List<MerchantPayment> merchant, List<GatewayPayment> gateway,
                                        Map<String, Settlement> settlements, Instant latestSettlementCutoff,
                                        Instant now, Settings settings) {
        List<Finding> findings = new ArrayList<>();
        Map<String, GatewayPayment> gwById = new HashMap<>();
        gateway.forEach(g -> gwById.put(g.id(), g));
        Set<String> linkedGatewayIds = new HashSet<>();
        Map<UUID, List<String>> paidGatewayPaymentsByOrder = new HashMap<>();

        for (MerchantPayment m : merchant) {
            if (m.gatewayPaymentId() == null) {
                continue; // unlinked attempts are matched from the gateway side below
            }
            linkedGatewayIds.add(m.gatewayPaymentId());
            GatewayPayment g = gwById.get(m.gatewayPaymentId());
            if (g == null) {
                findings.add(finding(MismatchType.GATEWAY_PAYMENT_NOT_FOUND, m, null, m.status().name(), "absent",
                        Map.of("merchantPaymentId", m.id().toString())));
                continue;
            }
            if (GatewayStateApplier.PAID_STATES.contains(g.status())) {
                paidGatewayPaymentsByOrder.computeIfAbsent(m.orderId(), k -> new ArrayList<>()).add(g.id());
            }
            compareOne(m, g, findings);
            compareSettlement(m, g, settlements.get(g.id()), latestSettlementCutoff, now, settings, findings);
        }

        for (GatewayPayment g : gateway) {
            if (!linkedGatewayIds.contains(g.id())) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("merchantOrderRef", g.merchantOrderRef());
                ev.put("gatewayStatus", g.status().name());
                ev.put("amountMinor", g.amountMinor());
                findings.add(new Finding(MismatchType.ORPHAN_GATEWAY_PAYMENT, g.id(), null, parseUuid(g.merchantOrderRef()),
                        "no linked payment", g.status().name(), ev));
            }
        }

        paidGatewayPaymentsByOrder.forEach((orderId, paid) -> {
            if (paid.size() > 1) {
                findings.add(new Finding(MismatchType.DOUBLE_CHARGE, null, null, orderId,
                        paid.size() + " paid payments", String.join(",", paid),
                        Map.of("paidGatewayPayments", paid, "action", "refund all but one")));
            }
        });
        return findings;
    }

    private static void compareOne(MerchantPayment m, GatewayPayment g, List<Finding> out) {
        Map<String, Object> ev = evidence(m, g);
        if (m.amountMinor() != g.amountMinor() || !m.currency().equals(g.currency())) {
            out.add(finding(MismatchType.AMOUNT_MISMATCH, m, g, m.amountMinor() + " " + m.currency(),
                    g.amountMinor() + " " + g.currency(), ev));
            return;
        }
        boolean merchantPaid = GatewayStateApplier.PAID_STATES.contains(m.status());
        boolean gatewayPaid = GatewayStateApplier.PAID_STATES.contains(g.status());

        if (g.version() > m.gatewayVersion()) {
            boolean reachable = m.status() == g.status() || PaymentStateMachine.isReachable(m.status(), g.status());
            if (!reachable) {
                out.add(finding(MismatchType.UNEXPECTED_STATE, m, g, state(m), state(g), ev));
                return;
            }
            out.add(finding(gatewayPaid && !merchantPaid ? MismatchType.GATEWAY_CAPTURED_MERCHANT_PENDING : MismatchType.STATE_LAG,
                    m, g, state(m), state(g), ev));
            if (m.maxReceivedWebhookVersion() < g.version()) {
                Map<String, Object> mw = new LinkedHashMap<>(ev);
                mw.put("missingVersions", (m.maxReceivedWebhookVersion() + 1) + ".." + g.version());
                out.add(finding(MismatchType.MISSING_WEBHOOK, m, g, "max received v" + m.maxReceivedWebhookVersion(),
                        "gateway v" + g.version(), mw));
            }
            return;
        }
        if (merchantPaid && !gatewayPaid) {
            out.add(finding(MismatchType.MERCHANT_CAPTURED_GATEWAY_NOT_CAPTURED, m, g, state(m), state(g), ev));
            return;
        }
        if (g.version() < m.gatewayVersion() || m.status() != g.status()) {
            out.add(finding(MismatchType.MERCHANT_AHEAD_OF_GATEWAY, m, g, state(m), state(g), ev));
        }
    }

    private static void compareSettlement(MerchantPayment m, GatewayPayment g, Settlement s, Instant latestCutoff,
                                          Instant now, Settings settings, List<Finding> out) {
        boolean settleable = g.status() == PaymentStatus.CAPTURED || g.status() == PaymentStatus.PARTIALLY_REFUNDED;
        if (s != null) {
            if (!s.currency().equals(g.currency()) || s.grossMinor() > g.amountMinor() || s.grossMinor() <= 0) {
                Map<String, Object> ev = evidence(m, g);
                ev.put("settlementBatch", s.batchId());
                out.add(finding(MismatchType.SETTLEMENT_AMOUNT_MISMATCH, m, g, "captured " + g.amountMinor() + " " + g.currency(),
                        "settled " + s.grossMinor() + " " + s.currency(), ev));
            }
            return;
        }
        if (!settleable || g.capturedAt() == null) {
            return;
        }
        Map<String, Object> ev = evidence(m, g);
        ev.put("capturedAt", g.capturedAt().toString());
        if (latestCutoff != null && !g.capturedAt().isAfter(latestCutoff)) {
            ev.put("latestCutoff", latestCutoff.toString());
            out.add(finding(MismatchType.MISSING_SETTLEMENT, m, g, "not settled", "captured before cutoff", ev));
        } else if (g.capturedAt().isBefore(now.minus(settings.settlementExpectedWithin()))) {
            ev.put("expectedWithin", settings.settlementExpectedWithin().toString());
            out.add(finding(MismatchType.DELAYED_SETTLEMENT, m, g, "not settled", "captured " + g.capturedAt(), ev));
        }
    }

    private static Finding finding(MismatchType t, MerchantPayment m, GatewayPayment g, String mv, String gv,
                                   Map<String, Object> evidence) {
        return new Finding(t, g != null ? g.id() : m.gatewayPaymentId(), m.id(), m.orderId(), mv, gv, evidence);
    }

    private static Map<String, Object> evidence(MerchantPayment m, GatewayPayment g) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("merchant", Map.of("status", m.status().name(), "gatewayVersion", m.gatewayVersion(),
                "amountMinor", m.amountMinor(), "currency", m.currency(), "refundedMinor", m.amountRefundedMinor()));
        ev.put("gateway", Map.of("status", g.status().name(), "version", g.version(),
                "amountMinor", g.amountMinor(), "currency", g.currency(), "refundedMinor", g.amountRefundedMinor()));
        return ev;
    }

    private static String state(MerchantPayment m) {
        return m.status() + " v" + m.gatewayVersion();
    }

    private static String state(GatewayPayment g) {
        return g.status() + " v" + g.version();
    }

    private static UUID parseUuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
