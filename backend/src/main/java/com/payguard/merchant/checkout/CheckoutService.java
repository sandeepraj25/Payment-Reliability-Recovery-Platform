package com.payguard.merchant.checkout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.common.crypto.RequestFingerprint;
import com.payguard.common.error.ApiException;
import com.payguard.idempotency.IdempotencyService;
import com.payguard.idempotency.IdempotencyService.Acquired;
import com.payguard.merchant.Order;
import com.payguard.merchant.OrderRepository;
import com.payguard.merchant.Payment;
import com.payguard.merchant.PaymentRepository;
import com.payguard.webhook.verification.PayGuardSimVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * POST /api/checkout: create an order + payment attempt, then register it with the gateway.
 *
 * <p>The request spans TWO systems (our DB and the gateway), so it cannot be one ACID
 * transaction. Instead it is split into steps with a durable recovery point after each:
 *
 * <pre>
 *  claim key ─► [TX1] insert order + payment, recovery_point=ORDER_CREATED ─► COMMIT
 *            ─► HTTP: gateway create payment (Idempotency-Key = "pgk_" + paymentId)   (no DB tx open)
 *            ─► [TX2] link gateway id, store response, status=COMPLETED ─► COMMIT
 * </pre>
 *
 * Crash after TX1: a retry (after the lease expires) resumes at ORDER_CREATED - it does not
 * create a second order. Crash after the HTTP call: the retry sends the SAME gateway key,
 * and the gateway returns the payment it already created. No step can double-charge.
 */
@Service
public class CheckoutService {

    static final String SCOPE = "POST /api/checkout";
    static final String RP_ORDER_CREATED = "ORDER_CREATED";

    private final IdempotencyService idempotency;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final GatewayClient gateway;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CheckoutService(IdempotencyService idempotency, OrderRepository orders, PaymentRepository payments,
                           GatewayClient gateway, TransactionTemplate tx, ObjectMapper objectMapper, Clock clock) {
        this.idempotency = idempotency;
        this.orders = orders;
        this.payments = payments;
        this.gateway = gateway;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public record CheckoutCommand(String customerRef, long amountMinor, String currency) {
    }

    public record CheckoutResponse(UUID orderId, UUID paymentId, String gatewayPaymentId, long amountMinor,
                                   String currency, String orderStatus, String paymentStatus) {
    }

    /** Status code + exact JSON body, so a replay returns byte-for-byte the original response. */
    public record HttpResult(int status, String body, boolean replayed) {
    }

    public HttpResult checkout(String idempotencyKey, CheckoutCommand cmd) {
        String fingerprint = RequestFingerprint.of(cmd.customerRef(), Long.toString(cmd.amountMinor()), cmd.currency());

        // Java 21 pattern matching over a sealed interface: the compiler forces us to handle every case.
        return switch (idempotency.claim(SCOPE, idempotencyKey, fingerprint)) {
            case IdempotencyService.Replay r -> new HttpResult(r.responseStatus(), r.responseBody(), true);
            case IdempotencyService.InFlight i -> throw ApiException.conflict("IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "A request with this Idempotency-Key is still being processed; retry shortly");
            case IdempotencyService.FingerprintMismatch m -> throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request");
            case Acquired a -> execute(a, cmd);
        };
    }

    private HttpResult execute(Acquired claim, CheckoutCommand cmd) {
        try {
            UUID paymentId = RP_ORDER_CREATED.equals(claim.recoveryPoint())
                    ? UUID.fromString(claim.resourceId())                      // resume after a crash
                    : tx.execute(s -> createOrderAndPayment(claim, cmd));      // TX1
            Payment payment = payments.findById(paymentId).orElseThrow();

            // No DB transaction is open here: never hold a connection or row lock while waiting on the network.
            GatewayClient.GatewayPaymentDto gp = gateway.createPayment(
                    gatewayKeyFor(paymentId), payment.getOrderId().toString(), payment.getAmountMinor(), payment.getCurrency());

            return tx.execute(s -> linkAndComplete(claim, paymentId, gp));    // TX2
        } catch (RuntimeException e) {
            // Let the client's retry resume immediately instead of waiting for the lease to expire.
            idempotency.release(claim);
            throw e;
        }
    }

    /** Deterministic: the same merchant payment ALWAYS uses the same gateway key. This is what makes retries safe. */
    static String gatewayKeyFor(UUID paymentId) {
        return "pgk_" + paymentId;
    }

    private UUID createOrderAndPayment(Acquired claim, CheckoutCommand cmd) {
        var now = clock.instant();
        Order order = orders.saveAndFlush(new Order(UUID.randomUUID(), cmd.customerRef(), cmd.amountMinor(), cmd.currency(), now));
        Payment payment = payments.saveAndFlush(new Payment(UUID.randomUUID(), order.getId(), PayGuardSimVerifier.PROVIDER,
                cmd.amountMinor(), cmd.currency(), now));
        // Same transaction as the inserts: "order exists" and "recovery point says so" commit together or not at all.
        idempotency.advance(claim, RP_ORDER_CREATED, payment.getId().toString());
        return payment.getId();
    }

    private HttpResult linkAndComplete(Acquired claim, UUID paymentId, GatewayClient.GatewayPaymentDto gp) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        if (gp.amountMinor() != payment.getAmountMinor() || !gp.currency().equals(payment.getCurrency())) {
            // Never paper over a money mismatch. Fail loudly; the record stays IN_PROGRESS for investigation.
            throw new IllegalStateException("gateway amount/currency mismatch for payment " + paymentId);
        }
        payment.linkGatewayPayment(gp.id(), clock.instant());
        Order order = orders.findById(payment.getOrderId()).orElseThrow();

        CheckoutResponse response = new CheckoutResponse(order.getId(), payment.getId(), gp.id(),
                payment.getAmountMinor(), payment.getCurrency(), order.getStatus().name(), payment.getStatus().name());
        String body = toJson(response);
        idempotency.complete(claim, HttpStatus.CREATED.value(), body);
        return new HttpResult(HttpStatus.CREATED.value(), body, false);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
