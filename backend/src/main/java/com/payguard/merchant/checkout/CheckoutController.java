package com.payguard.merchant.checkout;

import com.payguard.common.error.ApiException;
import com.payguard.merchant.Order;
import com.payguard.merchant.OrderRepository;
import com.payguard.merchant.Payment;
import com.payguard.merchant.PaymentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class CheckoutController {

    private final CheckoutService checkout;
    private final OrderRepository orders;
    private final PaymentRepository payments;

    public CheckoutController(CheckoutService checkout, OrderRepository orders, PaymentRepository payments) {
        this.checkout = checkout;
        this.orders = orders;
        this.payments = payments;
    }

    public record CheckoutRequest(
            @NotBlank @Size(max = 64) String customerRef,
            @NotNull @Positive @Max(100_000_000_00L) Long amountMinor,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {
    }

    public record PaymentView(UUID id, String provider, String gatewayPaymentId, long amountMinor,
                              long amountRefundedMinor, String currency, String status, Instant createdAt,
                              Instant updatedAt) {
        static PaymentView of(Payment p) {
            return new PaymentView(p.getId(), p.getProvider(), p.getGatewayPaymentId(), p.getAmountMinor(),
                    p.getAmountRefundedMinor(), p.getCurrency(), p.getStatus().name(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record OrderView(UUID id, String customerRef, long amountMinor, String currency, String status,
                            Instant fulfilledAt, Instant createdAt, List<PaymentView> payments) {
    }

    @PostMapping("/api/checkout")
    public ResponseEntity<String> checkout(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 255) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        CheckoutService.HttpResult r = checkout.checkout(idempotencyKey,
                new CheckoutService.CheckoutCommand(request.customerRef(), request.amountMinor(), request.currency()));
        return ResponseEntity.status(r.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", Boolean.toString(r.replayed()))
                .body(r.body());
    }

    @GetMapping("/api/orders/{orderId}")
    @Transactional(readOnly = true)
    public OrderView order(@PathVariable UUID orderId) {
        Order o = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        List<PaymentView> ps = payments.findByOrderIdOrderByCreatedAtAsc(orderId).stream().map(PaymentView::of).toList();
        return new OrderView(o.getId(), o.getCustomerRef(), o.getAmountMinor(), o.getCurrency(), o.getStatus().name(),
                o.getFulfilledAt(), o.getCreatedAt(), ps);
    }
}
