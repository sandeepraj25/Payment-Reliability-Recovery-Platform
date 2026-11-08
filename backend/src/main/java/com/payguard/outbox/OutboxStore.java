package com.payguard.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.messaging.RabbitTopology;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL for the transactional outbox. All writes join the caller's transaction. */
@Repository
public class OutboxStore {

    public enum Purpose { INGEST, RETRY, REPLAY, RECOVERY }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OutboxStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record PendingRow(long id, long webhookEventId, String routingKey, String payload, int publishAttempts) {
    }

    public record OutboxRow(long id, long webhookEventId, String purpose, String status, int publishAttempts,
                            OffsetDateTime nextAttemptAt, String lastError, OffsetDateTime createdAt,
                            OffsetDateTime publishedAt) {
    }

    /**
     * The message carries only a REFERENCE (webhookEventId). The worker always re-reads the event from
     * PostgreSQL, so a message can never carry stale or tampered business data.
     */
    public long insert(long webhookEventId, Purpose purpose, String providerEventId, String eventType,
                       String gatewayPaymentId, Instant nextAttemptAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("webhookEventId", webhookEventId);
        payload.put("providerEventId", providerEventId);
        payload.put("eventType", eventType);
        payload.put("gatewayPaymentId", gatewayPaymentId);
        payload.put("purpose", purpose.name());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return jdbc.sql("""
                INSERT INTO outbox_events (webhook_event_id, purpose, routing_key, payload, next_attempt_at)
                VALUES (:eventId, :purpose, :rk, CAST(:payload AS jsonb), :next)
                RETURNING id
                """)
                .param("eventId", webhookEventId)
                .param("purpose", purpose.name())
                .param("rk", RabbitTopology.ROUTING_KEY)
                .param("payload", json)
                .param("next", Timestamp.from(nextAttemptAt))
                .query(Long.class)
                .single();
    }

    /**
     * Claims due rows for THIS transaction.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}: rows another publisher instance is currently working on are
     * skipped instead of waited for. Two publishers therefore never send the same row at the same
     * time, and neither blocks the other. If this publisher crashes, its transaction rolls back,
     * the locks vanish, and the rows are simply claimed again on the next poll.
     */
    public List<PendingRow> claimDue(int limit, Instant now) {
        return jdbc.sql("""
                SELECT id, webhook_event_id, routing_key, payload::text AS payload, publish_attempts
                  FROM outbox_events
                 WHERE status = 'PENDING' AND next_attempt_at <= :now
                 ORDER BY next_attempt_at, id
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
                """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, n) -> new PendingRow(rs.getLong("id"), rs.getLong("webhook_event_id"),
                        rs.getString("routing_key"), rs.getString("payload"), rs.getInt("publish_attempts")))
                .list();
    }

    public void markPublished(List<Long> ids, Instant now) {
        jdbc.sql("UPDATE outbox_events SET status = 'PUBLISHED', published_at = :now, publish_attempts = publish_attempts + 1 WHERE id IN (:ids)")
                .param("now", Timestamp.from(now))
                .param("ids", ids)
                .update();
    }

    /**
     * Broker unreachable or confirm not received: the outcome is UNKNOWN (the broker may have the
     * message). We keep the rows PENDING and retry later with capped exponential backoff
     * (0.5s, 1s, 2s ... max 30s). The outbox never gives up - a committed event must stay recoverable.
     */
    public void markPublishFailed(List<Long> ids, String error, Instant now) {
        jdbc.sql("""
                UPDATE outbox_events
                   SET publish_attempts = publish_attempts + 1,
                       last_error = :error,
                       next_attempt_at = CAST(:now AS timestamptz) + LEAST(interval '30 seconds',
                                                      interval '500 milliseconds' * power(2, LEAST(publish_attempts, 10)))
                 WHERE id IN (:ids)
                """)
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 500)))
                .param("now", Timestamp.from(now))
                .param("ids", ids)
                .update();
    }

    public long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'").query(Long.class).single();
    }

    /** Age of the oldest row that is due but not published: the best "is the publisher stuck?" signal. */
    public long oldestDueAgeSeconds(Instant now) {
        return jdbc.sql("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) - min(created_at)))::bigint, 0)
                  FROM outbox_events WHERE status = 'PENDING' AND next_attempt_at <= CAST(:now AS timestamptz)
                """)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    public List<OutboxRow> forWebhookEvent(long webhookEventId) {
        return jdbc.sql("""
                SELECT id, webhook_event_id, purpose, status, publish_attempts, next_attempt_at, last_error,
                       created_at, published_at
                  FROM outbox_events WHERE webhook_event_id = :id ORDER BY id
                """)
                .param("id", webhookEventId)
                .query((rs, n) -> new OutboxRow(rs.getLong("id"), rs.getLong("webhook_event_id"),
                        rs.getString("purpose"), rs.getString("status"), rs.getInt("publish_attempts"),
                        rs.getObject("next_attempt_at", OffsetDateTime.class), rs.getString("last_error"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("published_at", OffsetDateTime.class)))
                .list();
    }
}
