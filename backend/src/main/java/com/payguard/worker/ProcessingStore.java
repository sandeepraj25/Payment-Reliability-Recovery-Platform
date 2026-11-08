package com.payguard.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** SQL for the worker's bookkeeping on webhook_events and processing_attempts. */
@Repository
public class ProcessingStore {

    private final JdbcClient jdbc;

    public ProcessingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record LockedEvent(long id, String provider, String providerEventId, String eventType,
                              String gatewayPaymentId, String rawBody, String status,
                              int attemptCount, int cycleAttempts) {
    }

    public record Attempt(long id, long webhookEventId, int attemptNo, String workerId, boolean redelivered,
                          String outcome, String errorClass, String errorMessage, Long retryDelayMs,
                          OffsetDateTime startedAt, OffsetDateTime finishedAt, int durationMs) {
    }

    public record Outcome(String outcome, String errorClass, String errorMessage, Long retryDelayMs) {
        public static Outcome of(String outcome) {
            return new Outcome(outcome, null, null, null);
        }
    }

    /**
     * Row lock on the event: if the same event is being handled twice at once (broker redelivery
     * racing a manual replay), the second handler waits here and then sees PROCESSED.
     */
    public Optional<LockedEvent> lockEvent(long id) {
        return jdbc.sql("""
                SELECT id, provider, provider_event_id, event_type, gateway_payment_id, raw_body,
                       processing_status, attempt_count, cycle_attempts
                  FROM webhook_events WHERE id = :id
                   FOR UPDATE
                """)
                .param("id", id)
                .query((rs, n) -> new LockedEvent(rs.getLong("id"), rs.getString("provider"),
                        rs.getString("provider_event_id"), rs.getString("event_type"),
                        rs.getString("gateway_payment_id"), rs.getString("raw_body"),
                        rs.getString("processing_status"), rs.getInt("attempt_count"), rs.getInt("cycle_attempts")))
                .optional();
    }

    /**
     * Records one attempt and updates the event in the same statement batch. The attempt number is
     * attempt_count + 1, read under the row lock above, so numbers are unique and gap-free.
     *
     * @param countsTowardsRetryBudget false for "skipped" outcomes (stale, already processed)
     */
    public int recordAttempt(LockedEvent e, String workerId, boolean redelivered, Outcome o,
                             Instant startedAt, Instant finishedAt, boolean countsTowardsRetryBudget) {
        int attemptNo = e.attemptCount() + 1;
        jdbc.sql("""
                INSERT INTO processing_attempts (webhook_event_id, attempt_no, worker_id, redelivered, outcome,
                    error_class, error_message, retry_delay_ms, started_at, finished_at, duration_ms)
                VALUES (:id, :no, :worker, :redelivered, :outcome, :errClass, :errMsg, :delay, :started, :finished, :duration)
                """)
                .param("id", e.id())
                .param("no", attemptNo)
                .param("worker", workerId)
                .param("redelivered", redelivered)
                .param("outcome", o.outcome())
                .param("errClass", truncate(o.errorClass(), 200))
                .param("errMsg", truncate(o.errorMessage(), 1000))
                .param("delay", o.retryDelayMs())
                .param("started", Timestamp.from(startedAt))
                .param("finished", Timestamp.from(finishedAt))
                .param("duration", (int) Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli()))
                .update();
        jdbc.sql("UPDATE webhook_events SET attempt_count = :no, cycle_attempts = cycle_attempts + :inc WHERE id = :id")
                .param("no", attemptNo)
                .param("inc", countsTowardsRetryBudget ? 1 : 0)
                .param("id", e.id())
                .update();
        return attemptNo;
    }

    public void markProcessed(long id, String result, Instant now) {
        jdbc.sql("""
                UPDATE webhook_events SET processing_status = 'PROCESSED', processing_result = :result,
                       processed_at = :now, last_error = NULL WHERE id = :id
                """)
                .param("result", result).param("now", Timestamp.from(now)).param("id", id)
                .update();
    }

    public void markRetryScheduled(long id, String error) {
        jdbc.sql("UPDATE webhook_events SET processing_status = 'RETRY_SCHEDULED', last_error = :err WHERE id = :id")
                .param("err", truncate(error, 1000)).param("id", id)
                .update();
    }

    public void markDead(long id, String error) {
        jdbc.sql("UPDATE webhook_events SET processing_status = 'DEAD', last_error = :err WHERE id = :id")
                .param("err", truncate(error, 1000)).param("id", id)
                .update();
    }

    /** Manual replay: a fresh retry budget, full history kept. */
    public void resetForReplay(long id) {
        jdbc.sql("""
                UPDATE webhook_events SET processing_status = 'RECEIVED', cycle_attempts = 0, processing_result = NULL
                 WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    public List<Attempt> attempts(long webhookEventId) {
        return jdbc.sql("""
                SELECT id, webhook_event_id, attempt_no, worker_id, redelivered, outcome, error_class, error_message,
                       retry_delay_ms, started_at, finished_at, duration_ms
                  FROM processing_attempts WHERE webhook_event_id = :id ORDER BY attempt_no
                """)
                .param("id", webhookEventId)
                .query((rs, n) -> new Attempt(rs.getLong("id"), rs.getLong("webhook_event_id"), rs.getInt("attempt_no"),
                        rs.getString("worker_id"), rs.getBoolean("redelivered"), rs.getString("outcome"),
                        rs.getString("error_class"), rs.getString("error_message"),
                        (Long) rs.getObject("retry_delay_ms"),
                        rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
                        rs.getInt("duration_ms")))
                .list();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
