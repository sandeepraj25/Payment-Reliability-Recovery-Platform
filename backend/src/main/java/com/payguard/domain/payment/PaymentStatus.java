package com.payguard.domain.payment;

/**
 * Lifecycle of a payment, shared by the gateway simulator and the merchant.
 *
 * <p>Settlement (money actually reaching the merchant's bank) is deliberately NOT a
 * status here. "Captured" means the customer was charged; "settled" happens days
 * later and is tracked separately (settlement_records, Phase 3). Mixing the two is
 * a classic reconciliation bug.
 */
public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    /** A refund was requested but the gateway has not confirmed it yet (async refunds, Phase 3). */
    REFUND_PENDING,
    PARTIALLY_REFUNDED,
    REFUNDED;

    public boolean isTerminal() {
        return this == FAILED || this == REFUNDED;
    }
}
