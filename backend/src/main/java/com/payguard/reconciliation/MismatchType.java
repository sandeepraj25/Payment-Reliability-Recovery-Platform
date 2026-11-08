package com.payguard.reconciliation;

/**
 * Every kind of disagreement reconciliation can find, with how serious it is and whether
 * PayGuard may fix it automatically. The auto-fix list is deliberately short: only cases where
 * the gateway's newer, versioned, amount-matching state can be applied through the normal guarded
 * path. Anything ambiguous goes to a human.
 */
public enum MismatchType {
    /** Customer paid, merchant does not know (lost/failed webhook). The headline case. */
    GATEWAY_CAPTURED_MERCHANT_PENDING(Severity.CRITICAL, Handling.AUTO_RECOVER),
    /** Gateway has a newer reachable state (e.g. FAILED, REFUNDED) the merchant has not applied. */
    STATE_LAG(Severity.WARNING, Handling.AUTO_RECOVER),
    /** Gateway versions exist that the merchant never received as webhooks. Evidence only. */
    MISSING_WEBHOOK(Severity.WARNING, Handling.INFORMATIONAL),
    /** Merchant believes CAPTURED but the gateway does not. Never auto-fixed (could be a forged event or a bug). */
    MERCHANT_CAPTURED_GATEWAY_NOT_CAPTURED(Severity.CRITICAL, Handling.MANUAL_REVIEW),
    /** Merchant has a higher gateway version than the gateway itself. Should be impossible. */
    MERCHANT_AHEAD_OF_GATEWAY(Severity.CRITICAL, Handling.MANUAL_REVIEW),
    /** Gateway state is not reachable from the merchant state (e.g. merchant FAILED, gateway CAPTURED). */
    UNEXPECTED_STATE(Severity.CRITICAL, Handling.MANUAL_REVIEW),
    /** Amount or currency differ. Never auto-fixed. */
    AMOUNT_MISMATCH(Severity.CRITICAL, Handling.MANUAL_REVIEW),
    /** Merchant payment points to a gateway payment the gateway does not report. */
    GATEWAY_PAYMENT_NOT_FOUND(Severity.CRITICAL, Handling.MANUAL_REVIEW),
    /** Gateway payment no merchant payment is linked to (e.g. checkout crashed before linking). */
    ORPHAN_GATEWAY_PAYMENT(Severity.WARNING, Handling.MANUAL_REVIEW),
    /** More than one payment for the same order is paid at the gateway: the customer paid twice -> refund. */
    DOUBLE_CHARGE(Severity.CRITICAL, Handling.MANUAL_REVIEW),
    /** Same event delivered many times. Not wrong (dedup handled it) but worth knowing. */
    DUPLICATE_DELIVERIES(Severity.INFO, Handling.INFORMATIONAL),
    /** Worker gave up on an event. Needs inspection and possibly a replay. */
    DEAD_LETTERED_EVENT(Severity.WARNING, Handling.MANUAL_REVIEW),
    /** Captured before the latest settlement cutoff but not in any settlement batch. */
    MISSING_SETTLEMENT(Severity.CRITICAL, Handling.MANUAL_REVIEW),
    /** Captured longer ago than settlement normally takes, and no batch has covered it yet. */
    DELAYED_SETTLEMENT(Severity.WARNING, Handling.INFORMATIONAL),
    /** Settled amount/currency inconsistent with the captured payment. */
    SETTLEMENT_AMOUNT_MISMATCH(Severity.CRITICAL, Handling.MANUAL_REVIEW);

    public enum Severity { INFO, WARNING, CRITICAL }

    public enum Handling { AUTO_RECOVER, MANUAL_REVIEW, INFORMATIONAL }

    private final Severity severity;
    private final Handling handling;

    MismatchType(Severity severity, Handling handling) {
        this.severity = severity;
        this.handling = handling;
    }

    public Severity severity() {
        return severity;
    }

    public Handling handling() {
        return handling;
    }
}
