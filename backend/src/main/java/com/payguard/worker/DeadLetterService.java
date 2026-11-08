package com.payguard.worker;

import com.payguard.audit.AuditLog;
import com.payguard.common.error.ApiException;
import com.payguard.outbox.OutboxStore;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Module F: dead-letter inspection and SAFE manual replay.
 *
 * <p>Why replay is safe: it does not "force" anything. It resets the retry budget and publishes the
 * same webhook event again through the outbox. The worker then runs the normal, fully guarded
 * path (version check, state machine, UNIQUE fulfilment). Replaying twice, or replaying an
 * event that a parallel redelivery already fixed, changes nothing.
 *
 * <p>Why it is auditable: who, when, why (mandatory reason) and before/after are written to
 * audit_logs in the same transaction as the reset.
 */
@Service
public class DeadLetterService {

    private final JdbcClient jdbc;
    private final ProcessingStore store;
    private final OutboxStore outbox;
    private final AuditLog audit;
    private final AmqpAdmin rabbitAdmin;
    private final Clock clock;

    public DeadLetterService(JdbcClient jdbc, ProcessingStore store, OutboxStore outbox, AuditLog audit,
                             AmqpAdmin rabbitAdmin, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.outbox = outbox;
        this.audit = audit;
        this.rabbitAdmin = rabbitAdmin;
        this.clock = clock;
    }

    public record DeadEvent(long webhookEventId, String providerEventId, String eventType, String gatewayPaymentId,
                            int attemptCount, String lastError, OffsetDateTime receivedAt, boolean replayable) {
    }

    public List<DeadEvent> list(int limit) {
        return jdbc.sql("""
                SELECT id, provider_event_id, event_type, gateway_payment_id, attempt_count, last_error, received_at
                  FROM webhook_events WHERE processing_status = 'DEAD'
                 ORDER BY received_at DESC LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, n) -> new DeadEvent(rs.getLong("id"), rs.getString("provider_event_id"),
                        rs.getString("event_type"), rs.getString("gateway_payment_id"), rs.getInt("attempt_count"),
                        rs.getString("last_error"), rs.getObject("received_at", OffsetDateTime.class), true))
                .list();
    }

    @Transactional
    public Map<String, Object> replay(long webhookEventId, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("REASON_REQUIRED", "A replay must state a reason (it is audited)");
        }
        ProcessingStore.LockedEvent e = store.lockEvent(webhookEventId)
                .orElseThrow(() -> ApiException.notFound("Webhook event", webhookEventId));
        if (!"DEAD".equals(e.status())) {
            throw ApiException.conflict("NOT_DEAD_LETTERED",
                    "Only DEAD events can be replayed; this one is " + e.status());
        }
        store.resetForReplay(e.id());
        long outboxId = outbox.insert(e.id(), OutboxStore.Purpose.REPLAY, e.providerEventId(), e.eventType(),
                e.gatewayPaymentId(), clock.instant());
        audit.record(actor, "DEAD_LETTER_REPLAYED", "webhook_event", Long.toString(e.id()), reason,
                Map.of("status", "DEAD", "attemptCount", e.attemptCount()),
                Map.of("status", "RECEIVED", "outboxId", outboxId));
        return Map.of("webhookEventId", e.id(), "status", "RECEIVED", "outboxId", outboxId);
    }

    /** Broker-side view (messages parked in the DLQ). The DB status DEAD is the source of truth. */
    public long brokerDlqDepth() {
        try {
            Properties p = rabbitAdmin.getQueueProperties(com.payguard.messaging.RabbitTopology.DLQ);
            Object count = p == null ? null : p.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            return count instanceof Number n ? n.longValue() : -1;
        } catch (RuntimeException brokerDown) {
            return -1;
        }
    }

    public long brokerQueueDepth() {
        try {
            Properties p = rabbitAdmin.getQueueProperties(com.payguard.messaging.RabbitTopology.QUEUE);
            Object count = p == null ? null : p.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            return count instanceof Number n ? n.longValue() : -1;
        } catch (RuntimeException brokerDown) {
            return -1;
        }
    }
}
