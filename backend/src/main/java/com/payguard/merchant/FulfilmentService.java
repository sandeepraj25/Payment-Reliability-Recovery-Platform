package com.payguard.merchant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Module C part 3: idempotent BUSINESS fulfilment ("ship the order").
 *
 * <p>Event dedup stops the same event twice. This stops the same ORDER twice, no matter which
 * path says "paid": two different webhook events, a webhook plus a reconciliation recovery,
 * or two concurrent workers. The guard is {@code UNIQUE(order_id)} on fulfilments - an
 * {@code INSERT ... ON CONFLICT DO NOTHING} either creates the one row or does nothing.
 */
@Service
public class FulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentService.class);

    public enum Source { WEBHOOK, RECONCILIATION, MANUAL }

    public enum Result { FULFILLED, ALREADY_FULFILLED, DOUBLE_CHARGE_SUSPECTED }

    private final JdbcClient jdbc;

    public FulfilmentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Must run inside the caller's transaction, with the order row already locked
     * (lock order everywhere: payment row, then order row - prevents deadlocks).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result fulfilIfFirst(Order order, Payment payment, Source source, String triggerRef, Instant now) {
        int inserted = jdbc.sql("""
                INSERT INTO fulfilments (order_id, payment_id, trigger_source, trigger_ref, created_at)
                VALUES (:orderId, :paymentId, :source, :ref, :now)
                ON CONFLICT (order_id) DO NOTHING
                """)
                .param("orderId", order.getId())
                .param("paymentId", payment.getId())
                .param("source", source.name())
                .param("ref", triggerRef)
                .param("now", java.sql.Timestamp.from(now))
                .update();
        if (inserted == 1) {
            order.markFulfilled(now);
            log.info("order fulfilled orderId={} paymentId={} source={} ref={}", order.getId(), payment.getId(), source, triggerRef);
            return Result.FULFILLED;
        }
        UUID fulfilledBy = paymentThatFulfilled(order.getId()).orElse(null);
        if (fulfilledBy != null && !fulfilledBy.equals(payment.getId())) {
            // A SECOND payment was captured for an order that already shipped: the customer paid twice.
            // We must not ship again - but this needs a refund. Reconciliation reports it.
            log.warn("double charge suspected orderId={} fulfilledByPayment={} extraPayment={}",
                    order.getId(), fulfilledBy, payment.getId());
            return Result.DOUBLE_CHARGE_SUSPECTED;
        }
        return Result.ALREADY_FULFILLED;
    }

    public Optional<UUID> paymentThatFulfilled(UUID orderId) {
        return jdbc.sql("SELECT payment_id FROM fulfilments WHERE order_id = :id")
                .param("id", orderId)
                .query(UUID.class)
                .optional();
    }

    public long countForOrder(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM fulfilments WHERE order_id = :id").param("id", orderId).query(Long.class).single();
    }
}
