package com.payguard.domain.payment;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.payguard.domain.payment.PaymentStatus.*;

/**
 * The single place that decides which status changes are legal.
 *
 * <pre>
 *   CREATED ──► AUTHORIZED ──► CAPTURED ──► PARTIALLY_REFUNDED ──► REFUNDED
 *      │            │              │   ▲          │   ▲  (another partial refund)
 *      └──► FAILED ◄┘              │   └──────────┘   │
 *                                  └──► REFUND_PENDING┘
 * </pre>
 *
 * Why a table instead of if/else scattered across services? One table is easy to
 * review, easy to test exhaustively (7x7 = 49 pairs), and impossible to bypass.
 */
public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(CREATED, EnumSet.of(AUTHORIZED, FAILED));
        ALLOWED.put(AUTHORIZED, EnumSet.of(CAPTURED, FAILED));
        ALLOWED.put(CAPTURED, EnumSet.of(REFUND_PENDING, PARTIALLY_REFUNDED, REFUNDED));
        ALLOWED.put(REFUND_PENDING, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
        // PARTIALLY_REFUNDED -> PARTIALLY_REFUNDED is a real change: a second partial refund.
        ALLOWED.put(PARTIALLY_REFUNDED, EnumSet.of(REFUND_PENDING, PARTIALLY_REFUNDED, REFUNDED));
        ALLOWED.put(FAILED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
    }

    private PaymentStateMachine() {
    }

    public enum Decision {
        /** Legal transition: apply it and emit an event. */
        APPLY,
        /** Already in the requested state: do nothing, emit nothing (makes retries idempotent). */
        NO_OP,
        /** Illegal transition: reject. */
        REJECT
    }

    public static Decision decide(PaymentStatus from, PaymentStatus to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (ALLOWED.get(from).contains(to)) {
            return Decision.APPLY;
        }
        if (from == to) {
            return Decision.NO_OP;
        }
        return Decision.REJECT;
    }

    /** Throws if the transition is illegal. A NO_OP is not an error. */
    public static void assertAllowed(PaymentStatus from, PaymentStatus to) {
        if (decide(from, to) == Decision.REJECT) {
            throw new InvalidStateTransitionException(from, to);
        }
    }

    /**
     * Can {@code to} be reached from {@code from} through one or more legal steps?
     *
     * <p>Used by the MERCHANT side, which learns about the gateway's state through webhooks that
     * can be lost or arrive out of order. Example: "captured" (version 2) arrives but "authorized"
     * (version 1) never did. CREATED -> CAPTURED is not a single legal step, but it IS reachable
     * (via AUTHORIZED), so the merchant may jump straight to the newer authoritative state.
     * CAPTURED -> FAILED is not reachable by any path, so such an event is a real anomaly.
     */
    public static boolean isReachable(PaymentStatus from, PaymentStatus to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        EnumSet<PaymentStatus> seen = EnumSet.noneOf(PaymentStatus.class);
        java.util.ArrayDeque<PaymentStatus> queue = new java.util.ArrayDeque<>(ALLOWED.get(from));
        while (!queue.isEmpty()) {        // breadth-first search over a 7-node graph
            PaymentStatus next = queue.poll();
            if (next == to) {
                return true;
            }
            if (seen.add(next)) {
                queue.addAll(ALLOWED.get(next));
            }
        }
        return false;
    }

    public static Set<PaymentStatus> allowedFrom(PaymentStatus from) {
        return Collections.unmodifiableSet(ALLOWED.get(from));
    }
}
