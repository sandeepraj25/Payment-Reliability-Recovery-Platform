package com.payguard.gateway;

import com.payguard.faults.FaultInjector;
import com.payguard.faults.FaultPoint;
import org.springframework.web.bind.annotation.RequestParam;
import com.payguard.domain.payment.PaymentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * HTTP API of the simulated gateway. In a demo, these endpoints play both roles a
 * real gateway has: the merchant's server API (create, refund, status) and the
 * "customer completed checkout" side (authorize, capture, fail).
 */
@RestController
@RequestMapping("/sim/gateway")
@ConditionalOnProperty(prefix = "payguard.simulator", name = "enabled", havingValue = "true")
public class GatewayController {

    private final GatewayPaymentService service;
    private final GatewayEventStore events;
    private final GatewayWebhookSender sender;

    private final GatewayPaymentRepository payments;
    private final GatewaySettlementService settlements;
    private final FaultInjector faults;
    private final long timeoutSleepMs;

    public GatewayController(GatewayPaymentService service, GatewayEventStore events, GatewayWebhookSender sender,
                             GatewayPaymentRepository payments, GatewaySettlementService settlements,
                             FaultInjector faults,
                             @org.springframework.beans.factory.annotation.Value("${payguard.simulator.timeout-sleep-ms:6000}") long timeoutSleepMs) {
        this.service = service;
        this.events = events;
        this.sender = sender;
        this.payments = payments;
        this.settlements = settlements;
        this.faults = faults;
        this.timeoutSleepMs = timeoutSleepMs;
    }

    // ---------- DTOs ----------

    public record CreatePaymentRequest(
            @NotBlank @Size(max = 64) String merchantOrderRef,
            @NotNull @Positive @Max(100_000_000_00L) Long amountMinor,   // up to Rs.10 crore in paise
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {
    }

    public record RefundRequest(@NotNull @Positive Long amountMinor) {
    }

    public record FailRequest(@Size(max = 255) String reason) {
    }

    public record PaymentView(String id, String gatewayReference, String merchantOrderRef, long amountMinor,
                              long amountRefundedMinor, String currency, PaymentStatus status,
                              String failureReason, long version, Instant capturedAt, Instant createdAt, Instant updatedAt) {
        static PaymentView of(GatewayPayment p) {
            return new PaymentView(p.getId(), p.getGatewayReference(), p.getMerchantOrderRef(), p.getAmountMinor(),
                    p.getAmountRefundedMinor(), p.getCurrency(), p.getStatus(), p.getFailureReason(),
                    p.getVersion(), p.getCapturedAt(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record RefundView(String id, String paymentId, long amountMinor, Instant createdAt, PaymentView payment) {
    }

    // ---------- endpoints ----------

    @PostMapping("/payments")
    public ResponseEntity<PaymentView> create(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 255) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        GatewayPaymentService.CreateResult result;
        try {
            result = service.create(idempotencyKey, request.merchantOrderRef(), request.amountMinor(), request.currency());
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // Another request with the same key inserted first (UNIQUE constraint).
            // Its transaction has committed, so a fresh attempt finds and returns that row.
            result = service.create(idempotencyKey, request.merchantOrderRef(), request.amountMinor(), request.currency());
        }
        if (!result.replayed() && faults.shouldFire(FaultPoint.GATEWAY_API_TIMEOUT)) {
            // The payment IS created and committed, but the answer arrives after the merchant's read timeout.
            // From the merchant's side the outcome is ambiguous: "did it work or not?"
            sleepQuietly(timeoutSleepMs);
        }
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(PaymentView.of(result.payment()));
    }

    /** Status API used by reconciliation: keyset-paginated list of payments changed since a time. */
    @GetMapping("/payments")
    public List<PaymentView> list(@RequestParam Instant updatedSince,
                                  @RequestParam(defaultValue = "") String afterId,
                                  @RequestParam(defaultValue = "200") int limit) {
        return payments.listUpdatedSince(updatedSince, afterId,
                        org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
                .stream().map(PaymentView::of).toList();
    }

    public record SettlementRunRequest(Instant cutoff) {
    }

    /** Produces a settlement batch (normally the gateway does this daily, T+1/T+2). */
    @PostMapping("/settlements/run")
    public GatewaySettlementService.Batch runSettlement(@RequestBody(required = false) SettlementRunRequest request) {
        return settlements.run(request == null || request.cutoff() == null ? Instant.now() : request.cutoff());
    }

    @GetMapping("/settlements")
    public List<GatewaySettlementService.Batch> settlementReports(@RequestParam Instant since) {
        return settlements.batchesSince(since);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentView get(@PathVariable String paymentId) {
        return PaymentView.of(service.get(paymentId));
    }

    @PostMapping("/payments/{paymentId}/authorize")
    public PaymentView authorize(@PathVariable String paymentId) {
        return PaymentView.of(service.authorize(paymentId));
    }

    @PostMapping("/payments/{paymentId}/capture")
    public PaymentView capture(@PathVariable String paymentId) {
        return PaymentView.of(service.capture(paymentId));
    }

    @PostMapping("/payments/{paymentId}/fail")
    public PaymentView fail(@PathVariable String paymentId,
                            @Valid @RequestBody(required = false) FailRequest request) {
        return PaymentView.of(service.fail(paymentId, request == null ? null : request.reason()));
    }

    @PostMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<RefundView> refund(
            @PathVariable String paymentId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 255) String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {
        GatewayPaymentService.RefundResult r;
        try {
            r = service.refund(paymentId, idempotencyKey, request.amountMinor());
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            r = service.refund(paymentId, idempotencyKey, request.amountMinor());
        }
        RefundView view = new RefundView(r.refund().getId(), paymentId, r.refund().getAmountMinor(),
                r.refund().getCreatedAt(), PaymentView.of(r.payment()));
        return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", Boolean.toString(r.replayed()))
                .body(view);
    }

    @GetMapping("/payments/{paymentId}/refunds")
    public List<RefundView> refunds(@PathVariable String paymentId) {
        return service.refundsOf(paymentId).stream()
                .map(r -> new RefundView(r.getId(), r.getPaymentId(), r.getAmountMinor(), r.getCreatedAt(), null))
                .toList();
    }

    @GetMapping("/payments/{paymentId}/events")
    public List<GatewayEventStore.StoredEvent> events(@PathVariable String paymentId) {
        service.get(paymentId);
        return events.findEventsForPayment(paymentId);
    }

    /** Re-sends an already-delivered event. This is how duplicate delivery is produced on demand. */
    @PostMapping("/events/{eventId}/redeliver")
    public GatewayWebhookSender.DeliveryResult redeliver(@PathVariable String eventId) {
        return sender.deliver(eventId, "MANUAL_REDELIVERY");
    }

    @GetMapping("/events/{eventId}/deliveries")
    public List<GatewayEventStore.Delivery> deliveries(@PathVariable String eventId) {
        return events.findDeliveries(eventId);
    }
}
