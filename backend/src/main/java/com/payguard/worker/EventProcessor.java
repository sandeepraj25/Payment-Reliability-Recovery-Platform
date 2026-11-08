package com.payguard.worker;

import com.payguard.domain.payment.PaymentStateMachine;
import com.payguard.domain.payment.PaymentStatus;
import com.payguard.faults.FaultPoint;
import com.payguard.faults.SimulatedCrashException;
import com.payguard.merchant.FulfilmentService;
import com.payguard.merchant.Order;
import com.payguard.merchant.OrderRepository;
import com.payguard.merchant.Payment;
import com.payguard.merchant.PaymentRepository;
import com.payguard.webhook.WebhookPayloadParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Module E: applies ONE stored webhook event to the merchant's payment and order, in ONE transaction.
 *
 * <pre>
 *  BEGIN
 *    lock webhook_events row      already PROCESSED? -> record SKIPPED_ALREADY_PROCESSED, done
 *    lock payments row            (lock order: event -> payment -> order, same everywhere => no deadlocks)
 *    version <= stored version?   -> stale/out-of-order -> SKIPPED_STALE, done
 *    target state reachable?      no -> permanent failure (anomaly for a human)
 *    apply gateway state
 *    lock orders row; paid? -> fulfilIfFirst (UNIQUE order_id)
 *    mark event PROCESSED, insert processing_attempts row
 *  COMMIT                         <- only after this does the listener ACK the message
 * </pre>
 *
 * Any exception rolls back EVERYTHING above: no half-applied payment, no fulfilment without
 * the status change, no "processed" marker without the work.
 */
@Service
public class EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    /** States that mean "the customer's money was captured at some point". */
    private static final Set<PaymentStatus> PAID_STATES = EnumSet.of(PaymentStatus.CAPTURED,
            PaymentStatus.REFUND_PENDING, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);

    public enum Result { APPLIED, NO_CHANGE, STALE_IGNORED, ALREADY_PROCESSED, IGNORED_TYPE }

    private final ProcessingStore store;
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final FulfilmentService fulfilment;
    private final WebhookPayloadParser parser;
    private final Clock clock;

    public EventProcessor(ProcessingStore store, PaymentRepository payments, OrderRepository orders,
                          FulfilmentService fulfilment, WebhookPayloadParser parser, Clock clock) {
        this.store = store;
        this.payments = payments;
        this.orders = orders;
        this.fulfilment = fulfilment;
        this.parser = parser;
        this.clock = clock;
    }

    /**
     * Faults decided by the caller BEFORE the transaction starts (see InjectedFaults), so that this
     * transaction never needs a second pooled connection.
     */
    public record InjectedFaults(boolean transientError, boolean crashBeforeCommit) {
        public static final InjectedFaults NONE = new InjectedFaults(false, false);
    }

    @Transactional
    public Result process(long webhookEventId, String workerId, boolean redelivered, Instant startedAt,
                          InjectedFaults injected) {
        ProcessingStore.LockedEvent e = store.lockEvent(webhookEventId)
                .orElseThrow(() -> new ProcessingException.Permanent("UNKNOWN_EVENT",
                        "webhook event " + webhookEventId + " does not exist"));

        switch (e.status()) {
            case "PROCESSED", "IGNORED", "DEAD" -> {
                // Redelivery after "commit but no ack", a duplicate publish, or a stale retry row.
                // DEAD events are only revived by an explicit, audited replay.
                store.recordAttempt(e, workerId, redelivered, ProcessingStore.Outcome.of("SKIPPED_ALREADY_PROCESSED"),
                        startedAt, clock.instant(), false);
                return Result.ALREADY_PROCESSED;
            }
            default -> { /* RECEIVED or RETRY_SCHEDULED: process it */ }
        }

        if (injected.transientError()) {
            throw new ProcessingException.Transient("SIMULATED_TRANSIENT", "simulated transient failure (fault injection)");
        }

        // The stored raw body was verified at ingestion; parsing again gives us typed fields.
        WebhookPayloadParser.ParsedEvent ev = parser.parse(e.rawBody().getBytes(StandardCharsets.UTF_8));
        if (!ev.known()) {
            store.markProcessed(e.id(), Result.IGNORED_TYPE.name(), clock.instant());
            store.recordAttempt(e, workerId, redelivered, ProcessingStore.Outcome.of("SUCCESS"), startedAt, clock.instant(), true);
            return Result.IGNORED_TYPE;
        }

        Payment payment = payments.findByGatewayIdForUpdate(e.provider(), ev.paymentId())
                // Can legitimately happen for a moment: the webhook overtook the checkout's "link gateway id"
                // commit. Transient -> retried with backoff; if it never resolves it becomes DEAD and
                // reconciliation will report the orphan.
                .orElseThrow(() -> new ProcessingException.Transient("PAYMENT_NOT_FOUND",
                        "no merchant payment linked to gateway payment " + ev.paymentId()));

        if (ev.amountMinor() != payment.getAmountMinor() || !ev.currency().equals(payment.getCurrency())) {
            throw new ProcessingException.Permanent("AMOUNT_MISMATCH", "event amount " + ev.amountMinor() + " "
                    + ev.currency() + " != merchant amount " + payment.getAmountMinor() + " " + payment.getCurrency());
        }

        Instant now = clock.instant();
        Result result;
        String outcome;

        if (ev.paymentVersion() <= payment.getGatewayVersion()) {
            // OUT-OF-ORDER / DELAYED: we already applied a newer (or the same) gateway version.
            // Applying this would move the payment BACKWARDS, e.g. CAPTURED -> AUTHORIZED.
            log.info("stale event ignored eventId={} paymentId={} eventVersion={} appliedVersion={}",
                    e.providerEventId(), ev.paymentId(), ev.paymentVersion(), payment.getGatewayVersion());
            result = Result.STALE_IGNORED;
            outcome = "SKIPPED_STALE";
        } else {
            PaymentStatus from = payment.getStatus();
            PaymentStatus to = ev.paymentStatus();
            boolean sameState = from == to && payment.getAmountRefundedMinor() == ev.amountRefundedMinor();
            if (from != to && !PaymentStateMachine.isReachable(from, to)) {
                // Newer version but an impossible move (e.g. CAPTURED -> FAILED). Never guess: escalate.
                throw new ProcessingException.Permanent("UNEXPECTED_TRANSITION",
                        "gateway reports " + to + " (v" + ev.paymentVersion() + ") but merchant has " + from
                                + " (v" + payment.getGatewayVersion() + "): not reachable");
            }
            payment.applyGatewayState(to, ev.amountRefundedMinor(), ev.paymentVersion(), now);
            result = sameState ? Result.NO_CHANGE : Result.APPLIED;
            outcome = "SUCCESS";
            applyOrderEffects(payment, e.providerEventId(), now);
        }

        store.markProcessed(e.id(), result.name(), now);
        store.recordAttempt(e, workerId, redelivered, ProcessingStore.Outcome.of(outcome), startedAt, clock.instant(), true);

        // Everything is written but NOT committed. A crash here must leave no trace (tested).
        if (injected.crashBeforeCommit()) {
            throw new SimulatedCrashException(FaultPoint.WORKER_CRASH_BEFORE_COMMIT);
        }
        return result;
    }

    private void applyOrderEffects(Payment payment, String eventId, Instant now) {
        Order order = orders.findByIdForUpdate(payment.getOrderId()).orElseThrow();
        if (PAID_STATES.contains(payment.getStatus())) {
            order.markPaid(now);
            fulfilment.fulfilIfFirst(order, payment, FulfilmentService.Source.WEBHOOK, eventId, now);
        } else if (payment.getStatus() == PaymentStatus.FAILED) {
            order.markPaymentFailed(now);
        }
    }
}
