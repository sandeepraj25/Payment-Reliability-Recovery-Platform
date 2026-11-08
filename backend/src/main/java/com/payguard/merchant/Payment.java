package com.payguard.merchant;

import com.payguard.domain.payment.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * The merchant's view of one payment attempt. Its status is updated from webhooks
 * (Phase 2 worker), so it can lag behind or disagree with the gateway - by design.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private String provider;

    @Column(name = "gateway_payment_id")
    private String gatewayPaymentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "amount_refunded_minor", nullable = false)
    private long amountRefundedMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(UUID id, UUID orderId, String provider, long amountMinor, String currency, Instant now) {
        this.id = id;
        this.orderId = orderId;
        this.provider = provider;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Called once the gateway confirms it created the payment. Safe to call again with the same id. */
    public void linkGatewayPayment(String gatewayPaymentId, Instant now) {
        if (this.gatewayPaymentId != null && !this.gatewayPaymentId.equals(gatewayPaymentId)) {
            throw new IllegalStateException("Payment " + id + " is already linked to " + this.gatewayPaymentId
                    + ", refusing to relink to " + gatewayPaymentId);
        }
        this.gatewayPaymentId = gatewayPaymentId;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public String getProvider() { return provider; }
    public String getGatewayPaymentId() { return gatewayPaymentId; }
    public long getAmountMinor() { return amountMinor; }
    public long getAmountRefundedMinor() { return amountRefundedMinor; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
