package com.payguard.merchant;

import com.payguard.domain.payment.PaymentStateMachine;
import com.payguard.domain.payment.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * The ONE place where the merchant copies gateway state onto its own payment and order.
 * Used by both the webhook worker and reconciliation recovery, so the two paths can never
 * disagree about what is safe:
 * <ol>
 *   <li>amount and currency must match            (else AMOUNT_MISMATCH - never auto-fixed)</li>
 *   <li>gateway version must be newer             (else STALE - ignored, never regresses)</li>
 *   <li>target state must be reachable            (else UNREACHABLE - never auto-fixed)</li>
 *   <li>apply, then order effects, then fulfilIfFirst (UNIQUE order_id)</li>
 * </ol>
 * Caller must hold the payment row lock (SELECT ... FOR UPDATE) inside its transaction.
 */
@Service
public class GatewayStateApplier {

    /** "The customer's money was captured at some point." */
    public static final Set<PaymentStatus> PAID_STATES = EnumSet.of(PaymentStatus.CAPTURED,
            PaymentStatus.REFUND_PENDING, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);

    public record GatewayState(PaymentStatus status, long version, long amountMinor, String currency,
                               long amountRefundedMinor) {
    }

    public enum Outcome { APPLIED, NO_CHANGE, STALE, AMOUNT_MISMATCH, UNREACHABLE }

    public record Result(Outcome outcome, PaymentStatus before, PaymentStatus after, long versionBefore,
                         FulfilmentService.Result fulfilment) {
    }

    private final OrderRepository orders;
    private final FulfilmentService fulfilment;

    public GatewayStateApplier(OrderRepository orders, FulfilmentService fulfilment) {
        this.orders = orders;
        this.fulfilment = fulfilment;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result apply(Payment payment, GatewayState s, FulfilmentService.Source source, String triggerRef, Instant now) {
        PaymentStatus before = payment.getStatus();
        long versionBefore = payment.getGatewayVersion();

        if (s.amountMinor() != payment.getAmountMinor() || !s.currency().equals(payment.getCurrency())) {
            return new Result(Outcome.AMOUNT_MISMATCH, before, before, versionBefore, null);
        }
        if (s.version() <= versionBefore) {
            return new Result(Outcome.STALE, before, before, versionBefore, null);
        }
        if (before != s.status() && !PaymentStateMachine.isReachable(before, s.status())) {
            return new Result(Outcome.UNREACHABLE, before, before, versionBefore, null);
        }

        boolean sameState = before == s.status() && payment.getAmountRefundedMinor() == s.amountRefundedMinor();
        payment.applyGatewayState(s.status(), s.amountRefundedMinor(), s.version(), now);

        FulfilmentService.Result f = null;
        Order order = orders.findByIdForUpdate(payment.getOrderId()).orElseThrow(); // lock order: payment -> order
        if (PAID_STATES.contains(payment.getStatus())) {
            order.markPaid(now);
            f = fulfilment.fulfilIfFirst(order, payment, source, triggerRef, now);
        } else if (payment.getStatus() == PaymentStatus.FAILED) {
            order.markPaymentFailed(now);
        }
        return new Result(sameState ? Outcome.NO_CHANGE : Outcome.APPLIED, before, payment.getStatus(), versionBefore, f);
    }
}
