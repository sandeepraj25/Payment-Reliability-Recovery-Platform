package com.payguard.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "customer_ref", nullable = false)
    private String customerRef;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
    }

    public Order(UUID id, String customerRef, long amountMinor, String currency, Instant now) {
        this.id = id;
        this.customerRef = customerRef;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = OrderStatus.PAYMENT_PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getCustomerRef() { return customerRef; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public OrderStatus getStatus() { return status; }
    public Instant getFulfilledAt() { return fulfilledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
