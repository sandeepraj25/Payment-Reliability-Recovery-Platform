package com.payguard.gateway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(schema = "gateway", name = "gateway_refunds")
public class GatewayRefund {

    @Id
    private String id;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GatewayRefund() {
    }

    public GatewayRefund(String id, String paymentId, long amountMinor, String idempotencyKey,
                         String requestFingerprint, Instant createdAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.amountMinor = amountMinor;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getPaymentId() { return paymentId; }
    public long getAmountMinor() { return amountMinor; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public Instant getCreatedAt() { return createdAt; }
}
