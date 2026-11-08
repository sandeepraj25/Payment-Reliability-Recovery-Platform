package com.payguard.gateway;

import com.payguard.domain.payment.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** The simulated gateway's authoritative payment record. */
@Entity
@Table(schema = "gateway", name = "gateway_payments")
public class GatewayPayment {

    @Id
    private String id;

    @Column(name = "gateway_reference", nullable = false)
    private String gatewayReference;

    @Column(name = "merchant_order_ref", nullable = false)
    private String merchantOrderRef;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "amount_refunded_minor", nullable = false)
    private long amountRefundedMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "create_idempotency_key", nullable = false)
    private String createIdempotencyKey;

    @Column(name = "create_request_fingerprint", nullable = false, length = 64)
    private String createRequestFingerprint;

    /**
     * Optimistic-lock version. Hibernate adds "WHERE version = ?" to every UPDATE and
     * increments it. It also doubles as the event sequence number sent in webhooks.
     */
    @Version
    @Column(nullable = false)
    private Long version; // wrapper type: null => "new entity" => persist() rather than merge()

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GatewayPayment() {
        // for JPA
    }

    public GatewayPayment(String id, String gatewayReference, String merchantOrderRef, long amountMinor,
                          String currency, String createIdempotencyKey, String createRequestFingerprint,
                          Instant now) {
        this.id = id;
        this.gatewayReference = gatewayReference;
        this.merchantOrderRef = merchantOrderRef;
        this.amountMinor = amountMinor;
        this.amountRefundedMinor = 0;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
        this.createIdempotencyKey = createIdempotencyKey;
        this.createRequestFingerprint = createRequestFingerprint;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Only the service calls this, after the state machine approved the transition. */
    void moveTo(PaymentStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    void markFailed(String reason, Instant now) {
        this.failureReason = reason;
        moveTo(PaymentStatus.FAILED, now);
    }

    void addRefund(long refundMinor, PaymentStatus next, Instant now) {
        this.amountRefundedMinor += refundMinor;
        moveTo(next, now);
    }

    public long refundableMinor() {
        return amountMinor - amountRefundedMinor;
    }

    public String getId() { return id; }
    public String getGatewayReference() { return gatewayReference; }
    public String getMerchantOrderRef() { return merchantOrderRef; }
    public long getAmountMinor() { return amountMinor; }
    public long getAmountRefundedMinor() { return amountRefundedMinor; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getCreateRequestFingerprint() { return createRequestFingerprint; }
    public long getVersion() { return version == null ? 0 : version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
