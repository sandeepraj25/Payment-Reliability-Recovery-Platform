package com.payguard.domain.payment;

public class InvalidStateTransitionException extends RuntimeException {

    private final PaymentStatus from;
    private final PaymentStatus to;

    public InvalidStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Illegal payment transition " + from + " -> " + to
                + " (allowed from " + from + ": " + PaymentStateMachine.allowedFrom(from) + ")");
        this.from = from;
        this.to = to;
    }

    public PaymentStatus from() {
        return from;
    }

    public PaymentStatus to() {
        return to;
    }
}
