package com.payguard.webhook;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SQL for webhook_events and webhook_rejections.
 *
 * <p>Why plain SQL instead of JPA {@code save()} here? Deduplication must be ONE atomic
 * database statement ({@code INSERT ... ON CONFLICT DO NOTHING}). The JPA pattern
 * "findById, if absent then save" has a race window between the SELECT and the INSERT:
 * two threads both see "absent" and both insert.
 */
@Repository
public class WebhookEventStore {

    private final JdbcClient jdbc;

    public WebhookEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record InsertResult(long id, boolean duplicate, String processingStatus, String existingPayloadSha256) {
    }

    public record EventRow(long id, String provider, String providerEventId, String eventType,
                           String gatewayPaymentId, Long gatewayPaymentVersion, OffsetDateTime providerCreatedAt,
                           String payload, String payloadSha256, String processingStatus, int duplicateCount,
                           OffsetDateTime lastDuplicateAt, OffsetDateTime receivedAt, OffsetDateTime processedAt) {
    }

    public record RejectionRow(long id, String provider, String claimedEventId, String reason, String detail,
                               String bodySha256, String sourceIp, OffsetDateTime receivedAt) {
    }

    /**
     * Inserts the event, or - if (provider, provider_event_id) already exists - counts a duplicate.
     * Must run inside the caller's transaction.
     *
     * <p>Concurrency, step by step (PostgreSQL, READ COMMITTED), for 1,000 simultaneous deliveries:
     * <ol>
     *   <li>One INSERT wins and holds the new index entry until its transaction ends.</li>
     *   <li>Every other INSERT ... ON CONFLICT finds the in-progress entry and WAITS for that transaction.</li>
     *   <li>Winner commits -> the waiters' ON CONFLICT fires -> DO NOTHING -> RETURNING yields no row.</li>
     *   <li>The following UPDATE is a NEW statement, so it gets a fresh snapshot that includes the winner's
     *       committed row, and increments duplicate_count under a row lock (no lost updates).</li>
     * </ol>
     * If the winner had rolled back instead, a waiter's INSERT would simply succeed and become the winner.
     */
    public InsertResult insertOrMarkDuplicate(String provider, WebhookPayloadParser.ParsedEvent e,
                                              String payloadSha256, Instant now) {
        Optional<Long> inserted = jdbc.sql("""
                INSERT INTO webhook_events
                    (provider, provider_event_id, event_type, gateway_payment_id, gateway_payment_version,
                     provider_created_at, raw_body, payload, payload_sha256, processing_status, received_at)
                VALUES (:provider, :eventId, :type, :paymentId, :version,
                        :createdAt, :raw, CAST(:raw AS jsonb), :sha, :status, :now)
                ON CONFLICT (provider, provider_event_id) DO NOTHING
                RETURNING id
                """)
                .param("provider", provider)
                .param("eventId", e.eventId())
                .param("type", e.type())
                .param("paymentId", e.paymentId())
                .param("version", e.paymentVersion())
                .param("createdAt", e.createdAt() == null ? null : Timestamp.from(e.createdAt()))
                .param("raw", e.rawBodyText())
                .param("sha", payloadSha256)
                .param("status", e.known() ? "RECEIVED" : "IGNORED")
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .optional();

        if (inserted.isPresent()) {
            return new InsertResult(inserted.get(), false, e.known() ? "RECEIVED" : "IGNORED", payloadSha256);
        }

        return jdbc.sql("""
                UPDATE webhook_events
                   SET duplicate_count = duplicate_count + 1, last_duplicate_at = :now
                 WHERE provider = :provider AND provider_event_id = :eventId
                RETURNING id, processing_status, payload_sha256
                """)
                .param("provider", provider)
                .param("eventId", e.eventId())
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new InsertResult(rs.getLong("id"), true,
                        rs.getString("processing_status"), rs.getString("payload_sha256")))
                .optional()
                // Only possible if the row was deleted between the two statements (retention job).
                // Throwing -> 500 -> the gateway retries later, which is safe.
                .orElseThrow(() -> new IllegalStateException("conflicting webhook event vanished: " + e.eventId()));
    }

    /** Autocommit insert (no surrounding transaction needed: one statement, nothing else depends on it). */
    public void insertRejection(String provider, String claimedEventId, RejectionReason reason, String detail,
                                String bodySha256, String bodyExcerpt, String sourceIp) {
        jdbc.sql("""
                INSERT INTO webhook_rejections (provider, claimed_event_id, reason, detail, body_sha256, body_excerpt, source_ip)
                VALUES (:provider, :claimed, :reason, :detail, :sha, :excerpt, :ip)
                """)
                .param("provider", provider)
                .param("claimed", truncate(claimedEventId, 128))
                .param("reason", reason.name())
                .param("detail", truncate(detail, 500))
                .param("sha", bodySha256)
                .param("excerpt", truncate(bodyExcerpt, 1000))
                .param("ip", truncate(sourceIp, 64))
                .update();
    }

    // ------------------------------------------------------------------ reads (operator API)

    private static final String EVENT_COLUMNS = """
            id, provider, provider_event_id, event_type, gateway_payment_id, gateway_payment_version,
            provider_created_at, payload::text AS payload, payload_sha256, processing_status, duplicate_count,
            last_duplicate_at, received_at, processed_at
            """;

    public List<EventRow> recentEvents(String gatewayPaymentId, int limit) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS + """
                  FROM webhook_events
                 WHERE (CAST(:paymentId AS varchar) IS NULL OR gateway_payment_id = :paymentId)
                 ORDER BY received_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("paymentId", gatewayPaymentId)
                .param("limit", limit)
                .query((rs, n) -> mapEvent(rs))
                .list();
    }

    public Optional<EventRow> findEvent(long id) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM webhook_events WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> mapEvent(rs))
                .optional();
    }

    public List<RejectionRow> recentRejections(int limit) {
        return jdbc.sql("""
                SELECT id, provider, claimed_event_id, reason, detail, body_sha256, source_ip, received_at
                  FROM webhook_rejections ORDER BY received_at DESC, id DESC LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, n) -> new RejectionRow(rs.getLong("id"), rs.getString("provider"),
                        rs.getString("claimed_event_id"), rs.getString("reason"), rs.getString("detail"),
                        rs.getString("body_sha256"), rs.getString("source_ip"),
                        rs.getObject("received_at", OffsetDateTime.class)))
                .list();
    }

    private static EventRow mapEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EventRow(
                rs.getLong("id"),
                rs.getString("provider"),
                rs.getString("provider_event_id"),
                rs.getString("event_type"),
                rs.getString("gateway_payment_id"),
                (Long) rs.getObject("gateway_payment_version"),
                rs.getObject("provider_created_at", OffsetDateTime.class),
                rs.getString("payload"),
                rs.getString("payload_sha256"),
                rs.getString("processing_status"),
                rs.getInt("duplicate_count"),
                rs.getObject("last_duplicate_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("processed_at", OffsetDateTime.class));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
