package com.payguard.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.common.crypto.RequestFingerprint;
import com.payguard.common.error.ApiException;
import com.payguard.common.id.Ids;
import com.payguard.domain.payment.InvalidStateTransitionException;
import com.payguard.domain.payment.PaymentStateMachine;
import com.payguard.domain.payment.PaymentStatus;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The simulated payment gateway's business logic.
 *
 * <p>Every state change follows the same recipe, all inside ONE database transaction:
 * <ol>
 *   <li>lock the payment row (SELECT ... FOR UPDATE)</li>
 *   <li>ask the state machine whether the transition is legal</li>
 *   <li>update the payment (version + 1)</li>
 *   <li>insert exactly one gateway_events row carrying that version</li>
 * </ol>
 * The webhook HTTP call happens only AFTER commit. If we called the merchant inside the
 * transaction and then rolled back, the merchant would have heard about a capture that
 * never happened.
 */
@Service
@ConditionalOnProperty(prefix = "payguard.simulator", name = "enabled", havingValue = "true")
public class GatewayPaymentService {

    private final GatewayPaymentRepository payments;
    private final GatewayRefundRepository refunds;
    private final GatewayEventStore events;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GatewayPaymentService(GatewayPaymentRepository payments, GatewayRefundRepository refunds,
                                 GatewayEventStore events, EntityManager entityManager,
                                 ApplicationEventPublisher publisher, ObjectMapper objectMapper, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.events = events;
        this.entityManager = entityManager;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public record CreateResult(GatewayPayment payment, boolean replayed) {
    }

    public record RefundResult(GatewayRefund refund, GatewayPayment payment, boolean replayed) {
    }

    // ------------------------------------------------------------------ create

    /**
     * Idempotent create. Two concurrent calls with the same key can both miss the
     * SELECT; the UNIQUE(create_idempotency_key) constraint then rejects the second
     * INSERT with DataIntegrityViolationException, and the caller retries once (see
     * GatewayController), which finds the winner's row. The database is the referee.
     */
    @Transactional
    public CreateResult create(String idempotencyKey, String merchantOrderRef, long amountMinor, String currency) {
        String fingerprint = RequestFingerprint.of(merchantOrderRef, Long.toString(amountMinor), currency);

        Optional<GatewayPayment> existing = payments.findByCreateIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getCreateRequestFingerprint().equals(fingerprint)) {
                throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request");
            }
            return new CreateResult(existing.get(), true);
        }

        GatewayPayment payment = new GatewayPayment(Ids.newId("pay"), Ids.newId("grf"), merchantOrderRef,
                amountMinor, currency, idempotencyKey, fingerprint, clock.instant());
        payments.saveAndFlush(payment); // flush NOW so a duplicate fails here, inside this call
        return new CreateResult(payment, false);
    }

    // ------------------------------------------------------------------ transitions

    @Transactional
    public GatewayPayment authorize(String paymentId) {
        return transition(paymentId, PaymentStatus.AUTHORIZED, "payment.authorized", null);
    }

    @Transactional
    public GatewayPayment capture(String paymentId) {
        return transition(paymentId, PaymentStatus.CAPTURED, "payment.captured", null);
    }

    @Transactional
    public GatewayPayment fail(String paymentId, String reason) {
        return transition(paymentId, PaymentStatus.FAILED, "payment.failed",
                reason == null || reason.isBlank() ? "declined_by_simulator" : reason);
    }

    private GatewayPayment transition(String paymentId, PaymentStatus target, String eventType, String failureReason) {
        GatewayPayment payment = lock(paymentId);
        Instant now = clock.instant();

        switch (PaymentStateMachine.decide(payment.getStatus(), target)) {
            case NO_OP -> {
                // e.g. "capture" retried after a timeout: already CAPTURED, nothing to do,
                // and crucially NO second event / webhook is produced.
                return payment;
            }
            case REJECT -> throw new InvalidStateTransitionException(payment.getStatus(), target);
            case APPLY -> {
                if (target == PaymentStatus.FAILED) {
                    payment.markFailed(failureReason, now);
                } else {
                    payment.moveTo(target, now);
                }
                payments.saveAndFlush(payment); // UPDATE ... SET version = version + 1
                emit(payment, eventType, null, now);
                return payment;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    // ------------------------------------------------------------------ refunds

    /**
     * Each refund is its own financial effect, so it has its own idempotency key.
     * The payment row lock makes "check existing refund -> check remaining amount ->
     * insert" atomic: two concurrent refunds cannot both see Rs.500 remaining.
     */
    @Transactional
    public RefundResult refund(String paymentId, String idempotencyKey, long amountMinor) {
        GatewayPayment payment = lock(paymentId);
        String fingerprint = RequestFingerprint.of(paymentId, Long.toString(amountMinor));

        Optional<GatewayRefund> existing = refunds.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestFingerprint().equals(fingerprint)) {
                throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different refund request");
            }
            return new RefundResult(existing.get(), payment, true);
        }

        if (payment.getStatus() != PaymentStatus.CAPTURED && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw ApiException.conflict("REFUND_NOT_ALLOWED",
                    "Only captured payments can be refunded; payment is " + payment.getStatus());
        }
        if (amountMinor > payment.refundableMinor()) {
            throw ApiException.unprocessable("REFUND_EXCEEDS_REMAINING",
                    "Refund " + amountMinor + " exceeds refundable amount " + payment.refundableMinor());
        }

        PaymentStatus target = amountMinor == payment.refundableMinor()
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        PaymentStateMachine.assertAllowed(payment.getStatus(), target); // defence in depth

        Instant now = clock.instant();
        GatewayRefund refund = new GatewayRefund(Ids.newId("rfnd"), paymentId, amountMinor,
                idempotencyKey, fingerprint, now);
        entityManager.persist(refund); // assigned id: persist() avoids merge()'s extra SELECT
        payment.addRefund(amountMinor, target, now);
        payments.saveAndFlush(payment);
        emit(payment, "payment.refunded", refund, now);
        return new RefundResult(refund, payment, false);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public GatewayPayment get(String paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment", paymentId));
    }

    @Transactional(readOnly = true)
    public List<GatewayRefund> refundsOf(String paymentId) {
        get(paymentId);
        return refunds.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    // ------------------------------------------------------------------ internals

    private GatewayPayment lock(String paymentId) {
        return payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment", paymentId));
    }

    /** Writes the event row in the current transaction and schedules delivery after commit. */
    private void emit(GatewayPayment payment, String eventType, GatewayRefund refund, Instant now) {
        String eventId = Ids.newId("evt");

        Map<String, Object> paymentJson = new LinkedHashMap<>();
        paymentJson.put("id", payment.getId());
        paymentJson.put("gateway_reference", payment.getGatewayReference());
        paymentJson.put("merchant_order_ref", payment.getMerchantOrderRef());
        paymentJson.put("amount_minor", payment.getAmountMinor());
        paymentJson.put("amount_refunded_minor", payment.getAmountRefundedMinor());
        paymentJson.put("currency", payment.getCurrency());
        paymentJson.put("status", payment.getStatus().name());
        paymentJson.put("version", payment.getVersion());
        if (payment.getFailureReason() != null) {
            paymentJson.put("failure_reason", payment.getFailureReason());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment", paymentJson);
        if (refund != null) {
            data.put("refund", Map.of("id", refund.getId(), "amount_minor", refund.getAmountMinor()));
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId);
        envelope.put("type", eventType);
        envelope.put("created_at", now.toString());
        envelope.put("data", data);

        try {
            events.insertEvent(eventId, payment.getId(), eventType, payment.getVersion(),
                    objectMapper.writeValueAsString(envelope), now);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize gateway event", e);
        }
        // Delivered by GatewayWebhookSender only if/when this transaction COMMITS.
        publisher.publishEvent(new GatewayEventCreated(eventId));
    }
}
