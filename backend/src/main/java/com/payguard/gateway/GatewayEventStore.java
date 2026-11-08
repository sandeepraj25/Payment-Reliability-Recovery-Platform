package com.payguard.gateway;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Plain SQL for gateway events and delivery logs. JSONB columns are written with
 * CAST(... AS jsonb) - explicit SQL keeps the database behaviour visible.
 */
@Repository
public class GatewayEventStore {

    private final JdbcClient jdbc;

    public GatewayEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredEvent(String id, String paymentId, String eventType, long paymentVersion,
                              String payload, OffsetDateTime createdAt) {
    }

    public record Delivery(long id, String eventId, String triggerType, Integer httpStatus, String error,
                           Integer durationMs, OffsetDateTime attemptedAt) {
    }

    public void insertEvent(String id, String paymentId, String eventType, long paymentVersion,
                            String payloadJson, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO gateway.gateway_events (id, payment_id, event_type, payment_version, payload, created_at)
                VALUES (:id, :paymentId, :eventType, :version, CAST(:payload AS jsonb), :createdAt)
                """)
                .param("id", id)
                .param("paymentId", paymentId)
                .param("eventType", eventType)
                .param("version", paymentVersion)
                .param("payload", payloadJson)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
    }

    public Optional<StoredEvent> findEvent(String eventId) {
        return jdbc.sql("""
                SELECT id, payment_id, event_type, payment_version, payload::text AS payload, created_at
                  FROM gateway.gateway_events WHERE id = :id
                """)
                .param("id", eventId)
                .query((rs, n) -> mapEvent(rs))
                .optional();
    }

    public List<StoredEvent> findEventsForPayment(String paymentId) {
        return jdbc.sql("""
                SELECT id, payment_id, event_type, payment_version, payload::text AS payload, created_at
                  FROM gateway.gateway_events WHERE payment_id = :paymentId ORDER BY payment_version
                """)
                .param("paymentId", paymentId)
                .query((rs, n) -> mapEvent(rs))
                .list();
    }

    public void recordDelivery(String eventId, String triggerType, Integer httpStatus, String error, int durationMs) {
        jdbc.sql("""
                INSERT INTO gateway.webhook_deliveries (event_id, trigger_type, http_status, error, duration_ms)
                VALUES (:eventId, :trigger, :status, :error, :duration)
                """)
                .param("eventId", eventId)
                .param("trigger", triggerType)
                .param("status", httpStatus)
                .param("error", error == null ? null : truncate(error, 500))
                .param("duration", durationMs)
                .update();
    }

    public List<Delivery> findDeliveries(String eventId) {
        return jdbc.sql("""
                SELECT id, event_id, trigger_type, http_status, error, duration_ms, attempted_at
                  FROM gateway.webhook_deliveries WHERE event_id = :eventId ORDER BY id
                """)
                .param("eventId", eventId)
                .query((rs, n) -> new Delivery(
                        rs.getLong("id"),
                        rs.getString("event_id"),
                        rs.getString("trigger_type"),
                        (Integer) rs.getObject("http_status"),
                        rs.getString("error"),
                        (Integer) rs.getObject("duration_ms"),
                        rs.getObject("attempted_at", OffsetDateTime.class)))
                .list();
    }

    private static StoredEvent mapEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredEvent(
                rs.getString("id"),
                rs.getString("payment_id"),
                rs.getString("event_type"),
                rs.getLong("payment_version"),
                rs.getString("payload"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
