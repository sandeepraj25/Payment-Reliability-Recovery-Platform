package com.payguard.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for idempotency_records. Every write after the claim includes
 * "AND lease_token = :token" - this is the fencing check (see V4 migration comment).
 */
@Repository
class IdempotencyStore {

    private final JdbcClient jdbc;

    IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(long id, String fingerprint, String status, String recoveryPoint, String resourceId,
               OffsetDateTime lockedUntil, Integer responseStatus, String responseBody, OffsetDateTime expiresAt) {
    }

    /**
     * Atomic "claim if nobody has it". If another transaction is inserting the same key
     * right now, PostgreSQL makes this statement WAIT for that transaction, then returns
     * no row (it committed) or inserts (it rolled back). No race window.
     */
    Optional<Long> tryInsert(String scope, String key, String fingerprint, UUID token,
                             Instant lockedUntil, Instant expiresAt) {
        return jdbc.sql("""
                INSERT INTO idempotency_records
                    (scope, idempotency_key, request_fingerprint, status, recovery_point,
                     lease_token, locked_until, expires_at)
                VALUES (:scope, :key, :fp, 'IN_PROGRESS', 'STARTED', :token, :lockedUntil, :expiresAt)
                ON CONFLICT (scope, idempotency_key) DO NOTHING
                RETURNING id
                """)
                .param("scope", scope)
                .param("key", key)
                .param("fp", fingerprint)
                .param("token", token)
                .param("lockedUntil", Timestamp.from(lockedUntil))
                .param("expiresAt", Timestamp.from(expiresAt))
                .query(Long.class)
                .optional();
    }

    /** Row lock: concurrent retries of the same key are evaluated one at a time. */
    Optional<Row> findForUpdate(String scope, String key) {
        return jdbc.sql("""
                SELECT id, request_fingerprint, status, recovery_point, resource_id, locked_until,
                       response_status, response_body, expires_at
                  FROM idempotency_records
                 WHERE scope = :scope AND idempotency_key = :key
                   FOR UPDATE
                """)
                .param("scope", scope)
                .param("key", key)
                .query((rs, n) -> new Row(
                        rs.getLong("id"),
                        rs.getString("request_fingerprint"),
                        rs.getString("status"),
                        rs.getString("recovery_point"),
                        rs.getString("resource_id"),
                        rs.getObject("locked_until", OffsetDateTime.class),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional();
    }

    /** Re-use an EXPIRED record's row for a brand new request. */
    void resetExpired(long id, String fingerprint, UUID token, Instant lockedUntil, Instant expiresAt, Instant now) {
        jdbc.sql("""
                UPDATE idempotency_records
                   SET request_fingerprint = :fp, status = 'IN_PROGRESS', recovery_point = 'STARTED',
                       resource_id = NULL, response_status = NULL, response_body = NULL,
                       lease_token = :token, locked_until = :lockedUntil, expires_at = :expiresAt,
                       created_at = :now, updated_at = :now
                 WHERE id = :id
                """)
                .param("id", id)
                .param("fp", fingerprint)
                .param("token", token)
                .param("lockedUntil", Timestamp.from(lockedUntil))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now))
                .update();
    }

    /** New owner after the previous owner's lease expired. The new token fences the old owner out. */
    void takeOver(long id, UUID token, Instant lockedUntil, Instant now) {
        jdbc.sql("""
                UPDATE idempotency_records
                   SET lease_token = :token, locked_until = :lockedUntil, updated_at = :now
                 WHERE id = :id
                """)
                .param("id", id)
                .param("token", token)
                .param("lockedUntil", Timestamp.from(lockedUntil))
                .param("now", Timestamp.from(now))
                .update();
    }

    int advance(long id, UUID token, String recoveryPoint, String resourceId, Instant now) {
        return jdbc.sql("""
                UPDATE idempotency_records
                   SET recovery_point = :rp, resource_id = :resourceId, updated_at = :now
                 WHERE id = :id AND lease_token = :token AND status = 'IN_PROGRESS'
                """)
                .param("id", id)
                .param("token", token)
                .param("rp", recoveryPoint)
                .param("resourceId", resourceId)
                .param("now", Timestamp.from(now))
                .update();
    }

    int complete(long id, UUID token, int responseStatus, String responseBody, Instant now) {
        return jdbc.sql("""
                UPDATE idempotency_records
                   SET status = 'COMPLETED', recovery_point = 'COMPLETED',
                       response_status = :status, response_body = :body,
                       lease_token = NULL, locked_until = NULL, updated_at = :now
                 WHERE id = :id AND lease_token = :token AND status = 'IN_PROGRESS'
                """)
                .param("id", id)
                .param("token", token)
                .param("status", responseStatus)
                .param("body", responseBody)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** End the lease early (request failed) so the client's retry can resume immediately. */
    int release(long id, UUID token, Instant now) {
        return jdbc.sql("""
                UPDATE idempotency_records
                   SET locked_until = :now, updated_at = :now
                 WHERE id = :id AND lease_token = :token AND status = 'IN_PROGRESS'
                """)
                .param("id", id)
                .param("token", token)
                .param("now", Timestamp.from(now))
                .update();
    }

    int deleteExpired(Instant now, int batchSize) {
        return jdbc.sql("""
                DELETE FROM idempotency_records
                 WHERE id IN (SELECT id FROM idempotency_records
                               WHERE expires_at < :now
                               ORDER BY expires_at
                               LIMIT :batch)
                """)
                .param("now", Timestamp.from(now))
                .param("batch", batchSize)
                .update();
    }
}
