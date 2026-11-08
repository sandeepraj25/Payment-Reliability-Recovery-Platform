package com.payguard.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.domain.payment.PaymentStatus;
import com.payguard.merchant.checkout.GatewayClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** SQL for reconciliation runs, mismatches and imported settlement reports. */
@Repository
public class ReconciliationStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ReconciliationStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record Run(long id, String triggerType, String triggeredBy, String status, OffsetDateTime windowFrom,
                      OffsetDateTime windowTo, int merchantPayments, int gatewayPayments, int mismatchesFound,
                      int autoResolved, int manualReview, String error, OffsetDateTime startedAt,
                      OffsetDateTime finishedAt) {
    }

    public record Mismatch(long id, long runId, String mismatchType, String severity, String gatewayPaymentId,
                           UUID merchantPaymentId, UUID orderId, String merchantValue, String gatewayValue,
                           String evidence, String resolution, String resolutionNote, String resolvedBy,
                           OffsetDateTime resolvedAt, OffsetDateTime createdAt) {
    }

    // ------------------------------------------------------------------ runs

    /** Crash safety: a run stuck in RUNNING (its process died) would block all future runs forever. */
    public int abandonStaleRuns(Instant olderThan) {
        return jdbc.sql("""
                UPDATE reconciliation_runs SET status = 'ABANDONED', finished_at = now(),
                       error = 'run did not finish (process crashed?)'
                 WHERE status = 'RUNNING' AND started_at < :t
                """).param("t", Timestamp.from(olderThan)).update();
    }

    /** Throws DuplicateKeyException if another run is RUNNING (partial unique index uq_recon_one_running). */
    public long startRun(String triggerType, String triggeredBy, Instant from, Instant to) {
        return jdbc.sql("""
                INSERT INTO reconciliation_runs (trigger_type, triggered_by, status, window_from, window_to)
                VALUES (:t, :by, 'RUNNING', :from, :to) RETURNING id
                """)
                .param("t", triggerType).param("by", triggeredBy)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query(Long.class).single();
    }

    public void finishRun(long id, int merchantPayments, int gatewayPayments, int found, int auto, int manual) {
        jdbc.sql("""
                UPDATE reconciliation_runs SET status = 'COMPLETED', finished_at = now(), merchant_payments = :m,
                       gateway_payments = :g, mismatches_found = :f, auto_resolved = :a, manual_review = :r
                 WHERE id = :id
                """)
                .param("m", merchantPayments).param("g", gatewayPayments).param("f", found)
                .param("a", auto).param("r", manual).param("id", id).update();
    }

    public void failRun(long id, String error) {
        jdbc.sql("UPDATE reconciliation_runs SET status = 'FAILED', finished_at = now(), error = :e WHERE id = :id")
                .param("e", error == null ? null : error.substring(0, Math.min(error.length(), 1000)))
                .param("id", id).update();
    }

    public List<Run> runs(int limit) {
        return jdbc.sql("SELECT * FROM reconciliation_runs ORDER BY id DESC LIMIT :l").param("l", limit)
                .query((rs, n) -> mapRun(rs)).list();
    }

    public Optional<Run> run(long id) {
        return jdbc.sql("SELECT * FROM reconciliation_runs WHERE id = :id").param("id", id)
                .query((rs, n) -> mapRun(rs)).optional();
    }

    // ------------------------------------------------------------------ mismatches

    public long insertMismatch(long runId, ReconciliationRules.Finding f, String resolution, String note, String resolvedBy) {
        return jdbc.sql("""
                INSERT INTO reconciliation_mismatches (run_id, mismatch_type, severity, gateway_payment_id,
                    merchant_payment_id, order_id, provider_event_id, merchant_value, gateway_value, evidence, resolution,
                    resolution_note, resolved_by, resolved_at)
                VALUES (:run, :type, :sev, :gp, :mp, :order, :pe, :mv, :gv, CAST(:ev AS jsonb), :res, :note, :by,
                        CASE WHEN :res IN ('AUTO_RESOLVED','ALREADY_CONSISTENT') THEN now() END)
                RETURNING id
                """)
                .param("run", runId).param("type", f.type().name()).param("sev", f.type().severity().name())
                .param("gp", f.gatewayPaymentId()).param("mp", f.merchantPaymentId()).param("order", f.orderId())
                .param("pe", f.evidence().get("providerEventId") == null ? null : f.evidence().get("providerEventId").toString())
                .param("mv", truncate(f.merchantValue(), 200)).param("gv", truncate(f.gatewayValue(), 200))
                .param("ev", toJson(f.evidence())).param("res", resolution).param("note", truncate(note, 500))
                .param("by", resolvedBy)
                .query(Long.class).single();
    }

    public List<Mismatch> mismatches(Long runId, String resolution, int limit) {
        return jdbc.sql("""
                SELECT id, run_id, mismatch_type, severity, gateway_payment_id, merchant_payment_id, order_id,
                       merchant_value, gateway_value, evidence::text AS evidence, resolution, resolution_note,
                       resolved_by, resolved_at, created_at
                  FROM reconciliation_mismatches
                 WHERE (CAST(:run AS bigint) IS NULL OR run_id = :run)
                   AND (CAST(:res AS varchar) IS NULL OR resolution = :res)
                 ORDER BY id DESC LIMIT :l
                """)
                .param("run", runId).param("res", resolution).param("l", limit)
                .query((rs, n) -> new Mismatch(rs.getLong("id"), rs.getLong("run_id"), rs.getString("mismatch_type"),
                        rs.getString("severity"), rs.getString("gateway_payment_id"),
                        rs.getObject("merchant_payment_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getString("merchant_value"), rs.getString("gateway_value"), rs.getString("evidence"),
                        rs.getString("resolution"), rs.getString("resolution_note"), rs.getString("resolved_by"),
                        rs.getObject("resolved_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    /** Only OPEN items can be closed by a human; returns false if it was not OPEN. */
    public boolean resolveManually(long mismatchId, String actor, String note) {
        return jdbc.sql("""
                UPDATE reconciliation_mismatches SET resolution = 'RESOLVED_MANUALLY', resolution_note = :note,
                       resolved_by = :by, resolved_at = now()
                 WHERE id = :id AND resolution = 'OPEN'
                """).param("note", truncate(note, 500)).param("by", actor).param("id", mismatchId).update() == 1;
    }

    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM reconciliation_mismatches WHERE resolution = 'OPEN'").query(Long.class).single();
    }

    // ------------------------------------------------------------------ inputs

    public List<ReconciliationRules.MerchantPayment> merchantPayments(Instant since) {
        return jdbc.sql("""
                SELECT p.id, p.order_id, p.gateway_payment_id, p.amount_minor, p.amount_refunded_minor, p.currency,
                       p.status, p.gateway_version, p.created_at,
                       COALESCE((SELECT max(e.gateway_payment_version) FROM webhook_events e
                                  WHERE e.gateway_payment_id = p.gateway_payment_id), 0) AS max_received
                  FROM payments p
                 WHERE p.created_at >= :since
                 ORDER BY p.created_at
                """)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> new ReconciliationRules.MerchantPayment(rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getString("gateway_payment_id"),
                        rs.getLong("amount_minor"), rs.getLong("amount_refunded_minor"), rs.getString("currency"),
                        PaymentStatus.valueOf(rs.getString("status")), rs.getLong("gateway_version"),
                        rs.getLong("max_received"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public record EventFinding(long id, String providerEventId, String gatewayPaymentId, int duplicateCount,
                               String status, String lastError) {
    }

    public List<EventFinding> noisyOrDeadEvents(Instant since, int duplicateThreshold) {
        return jdbc.sql("""
                SELECT id, provider_event_id, gateway_payment_id, duplicate_count, processing_status, last_error
                  FROM webhook_events
                 WHERE received_at >= :since AND (duplicate_count >= :th OR processing_status = 'DEAD')
                """)
                .param("since", Timestamp.from(since)).param("th", duplicateThreshold)
                .query((rs, n) -> new EventFinding(rs.getLong("id"), rs.getString("provider_event_id"),
                        rs.getString("gateway_payment_id"), rs.getInt("duplicate_count"),
                        rs.getString("processing_status"), rs.getString("last_error")))
                .list();
    }

    // ------------------------------------------------------------------ settlement import

    /** Idempotent: importing the same report twice inserts nothing the second time. */
    public int importSettlementBatch(GatewayClient.SettlementBatch b) {
        jdbc.sql("""
                INSERT INTO settlement_batches (batch_id, cutoff, item_count) VALUES (:id, :cutoff, :n)
                ON CONFLICT (batch_id) DO NOTHING
                """).param("id", b.id()).param("cutoff", Timestamp.from(b.cutoff())).param("n", b.items().size()).update();
        int inserted = 0;
        for (GatewayClient.SettlementItem i : b.items()) {
            inserted += jdbc.sql("""
                    INSERT INTO settlement_records (batch_id, gateway_payment_id, gross_minor, fee_minor, net_minor, currency)
                    VALUES (:b, :p, :g, :f, :n, :c) ON CONFLICT (batch_id, gateway_payment_id) DO NOTHING
                    """).param("b", b.id()).param("p", i.paymentId()).param("g", i.grossMinor())
                    .param("f", i.feeMinor()).param("n", i.netMinor()).param("c", i.currency()).update();
        }
        return inserted;
    }

    public Map<String, ReconciliationRules.Settlement> settlementsByGatewayPayment() {
        Map<String, ReconciliationRules.Settlement> map = new HashMap<>();
        jdbc.sql("SELECT gateway_payment_id, batch_id, gross_minor, currency FROM settlement_records")
                .query((rs, n) -> map.put(rs.getString(1), new ReconciliationRules.Settlement(rs.getString(2), rs.getLong(3), rs.getString(4))))
                .list();
        return map;
    }

    public Optional<Instant> latestSettlementCutoff() {
        // max() over zero rows returns ONE row containing NULL - handle that explicitly.
        List<Instant> r = jdbc.sql("SELECT max(cutoff) FROM settlement_batches")
                .query((rs, n) -> {
                    OffsetDateTime t = rs.getObject(1, OffsetDateTime.class);
                    return t == null ? null : t.toInstant();
                })
                .list();
        return r.isEmpty() ? Optional.empty() : Optional.ofNullable(r.get(0));
    }

    // ------------------------------------------------------------------ helpers

    private static Run mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Run(rs.getLong("id"), rs.getString("trigger_type"), rs.getString("triggered_by"),
                rs.getString("status"), rs.getObject("window_from", OffsetDateTime.class),
                rs.getObject("window_to", OffsetDateTime.class), rs.getInt("merchant_payments"),
                rs.getInt("gateway_payments"), rs.getInt("mismatches_found"), rs.getInt("auto_resolved"),
                rs.getInt("manual_review"), rs.getString("error"), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
