package com.payguard.ops;

import com.payguard.audit.AuditLog;
import com.payguard.common.error.ApiException;
import com.payguard.merchant.checkout.GatewayClient;
import com.payguard.outbox.OutboxStore;
import com.payguard.reconciliation.ReconciliationStore;
import com.payguard.worker.DeadLetterService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only operations API behind the dashboard. Every number here is a SQL query or a broker
 * call made at request time - there are no cached or hardcoded metrics.
 */
@RestController
public class OpsController {

    private final JdbcClient jdbc;
    private final OutboxStore outbox;
    private final DeadLetterService deadLetters;
    private final ReconciliationStore reconciliation;
    private final GatewayClient gateway;
    private final AuditLog audit;
    private final Clock clock;

    public OpsController(JdbcClient jdbc, OutboxStore outbox, DeadLetterService deadLetters,
                         ReconciliationStore reconciliation, GatewayClient gateway, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.deadLetters = deadLetters;
        this.reconciliation = reconciliation;
        this.gateway = gateway;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ dashboard

    @GetMapping("/api/ops/summary")
    public Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("paymentsByStatus", groupCount("SELECT status, count(*) FROM payments GROUP BY status"));
        s.put("ordersByStatus", groupCount("SELECT status, count(*) FROM orders GROUP BY status"));
        s.put("webhookEventsByStatus", groupCount("SELECT processing_status, count(*) FROM webhook_events GROUP BY processing_status"));
        s.put("totalPayments", scalar("SELECT count(*) FROM payments"));
        s.put("successfulPayments", scalar("SELECT count(*) FROM payments WHERE status IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED','REFUND_PENDING')"));
        s.put("pendingPayments", scalar("SELECT count(*) FROM payments WHERE status IN ('CREATED','AUTHORIZED')"));
        s.put("failedEvents", scalar("SELECT count(*) FROM webhook_events WHERE processing_status = 'DEAD'"));
        s.put("retryScheduled", scalar("SELECT count(*) FROM webhook_events WHERE processing_status = 'RETRY_SCHEDULED'"));
        s.put("rejectedWebhooks", scalar("SELECT count(*) FROM webhook_rejections"));
        s.put("duplicateDeliveries", scalar("SELECT COALESCE(sum(duplicate_count), 0) FROM webhook_events"));
        s.put("recoveredByReconciliation", scalar("SELECT count(*) FROM fulfilments WHERE trigger_source = 'RECONCILIATION'"));
        s.put("replayedDeadLetters", scalar("SELECT count(*) FROM audit_logs WHERE action = 'DEAD_LETTER_REPLAYED'"));
        s.put("fulfilments", scalar("SELECT count(*) FROM fulfilments"));
        s.put("outboxBacklog", outbox.pendingCount());
        s.put("outboxOldestDueAgeSeconds", outbox.oldestDueAgeSeconds(clock.instant()));
        s.put("queueDepth", deadLetters.brokerQueueDepth());
        s.put("dlqDepth", deadLetters.brokerDlqDepth());
        s.put("openMismatches", reconciliation.openCount());
        s.put("lastReconciliation", reconciliation.runs(1).stream().findFirst().orElse(null));
        return s;
    }

    /** Per-minute activity for the last 60 minutes (charts). */
    @GetMapping("/api/ops/timeseries")
    public List<Map<String, Object>> timeseries() {
        return jdbc.sql("""
                WITH minutes AS (
                    SELECT generate_series(date_trunc('minute', now()) - interval '59 minutes',
                                           date_trunc('minute', now()), interval '1 minute') AS m)
                SELECT to_char(m.m, 'HH24:MI') AS minute,
                       (SELECT count(*) FROM webhook_events e WHERE date_trunc('minute', e.received_at) = m.m) AS received,
                       (SELECT count(*) FROM webhook_rejections r WHERE date_trunc('minute', r.received_at) = m.m) AS rejected,
                       (SELECT count(*) FROM processing_attempts a WHERE date_trunc('minute', a.finished_at) = m.m
                           AND a.outcome = 'SUCCESS') AS processed,
                       (SELECT count(*) FROM processing_attempts a WHERE date_trunc('minute', a.finished_at) = m.m
                           AND a.outcome IN ('TRANSIENT_FAILURE','PERMANENT_FAILURE')) AS failed,
                       (SELECT count(*) FROM processing_attempts a WHERE date_trunc('minute', a.finished_at) = m.m
                           AND a.outcome LIKE 'SKIPPED%') AS skipped
                  FROM minutes m ORDER BY m.m
                """)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("minute", rs.getString("minute"));
                    for (String k : List.of("received", "rejected", "processed", "failed", "skipped")) row.put(k, rs.getLong(k));
                    return row;
                })
                .list();
    }

    // ------------------------------------------------------------------ system health

    @GetMapping("/api/ops/health")
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        boolean dbUp;
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            dbUp = true;
        } catch (RuntimeException e) {
            dbUp = false;
        }
        h.put("database", Map.of("up", dbUp, "latencyMs", (System.nanoTime() - t0) / 1_000_000));
        long depth = deadLetters.brokerQueueDepth();
        h.put("rabbitmq", Map.of("up", depth >= 0, "queueDepth", depth, "dlqDepth", deadLetters.brokerDlqDepth()));
        // Worker liveness from the database: works even when workers run in other processes or hosts.
        h.put("workers", jdbc.sql("""
                SELECT worker_id, max(finished_at) AS last_seen, count(*) AS attempts
                  FROM processing_attempts WHERE finished_at > now() - interval '15 minutes'
                 GROUP BY worker_id ORDER BY last_seen DESC
                """).query((rs, n) -> Map.of("workerId", rs.getString(1),
                        "lastSeen", rs.getObject(2, OffsetDateTime.class).toString(), "attempts15m", rs.getLong(3))).list());
        h.put("outbox", Map.of("backlog", outbox.pendingCount(), "oldestDueAgeSeconds", outbox.oldestDueAgeSeconds(clock.instant())));
        h.put("processingLatencyMs", jdbc.sql("""
                SELECT COALESCE(percentile_cont(0.5)  WITHIN GROUP (ORDER BY duration_ms), 0) AS p50,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0) AS p95,
                       COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms), 0) AS p99,
                       count(*) AS samples
                  FROM processing_attempts WHERE outcome = 'SUCCESS' AND finished_at > now() - interval '1 hour'
                """).query((rs, n) -> Map.of("p50", rs.getDouble(1), "p95", rs.getDouble(2), "p99", rs.getDouble(3),
                "samples", rs.getLong(4))).single());
        h.put("receiveToProcessedMs", jdbc.sql("""
                SELECT COALESCE(percentile_cont(0.5)  WITHIN GROUP (ORDER BY ms), 0),
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms), 0), count(*)
                  FROM (SELECT EXTRACT(EPOCH FROM (processed_at - received_at)) * 1000 AS ms
                          FROM webhook_events WHERE processed_at > now() - interval '1 hour') x
                """).query((rs, n) -> Map.of("p50", rs.getDouble(1), "p95", rs.getDouble(2), "samples", rs.getLong(3))).single());
        return h;
    }

    // ------------------------------------------------------------------ payment explorer

    @GetMapping("/api/payments")
    public List<Map<String, Object>> payments(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "100") int limit) {
        String query = q == null || q.isBlank() ? null : q.trim();
        return jdbc.sql("""
                SELECT p.id, p.order_id, p.gateway_payment_id, p.amount_minor, p.amount_refunded_minor, p.currency,
                       p.status, p.gateway_version, o.status AS order_status, p.created_at, p.updated_at
                  FROM payments p JOIN orders o ON o.id = p.order_id
                 WHERE (CAST(:status AS varchar) IS NULL OR p.status = :status)
                   AND (CAST(:q AS varchar) IS NULL OR p.id::text = :q OR p.order_id::text = :q OR p.gateway_payment_id = :q)
                 ORDER BY p.created_at DESC LIMIT :limit
                """)
                .param("status", status == null || status.isBlank() ? null : status)
                .param("q", query)
                .param("limit", Math.max(1, Math.min(limit, 500)))
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("orderId", rs.getString("order_id"));
                    m.put("gatewayPaymentId", rs.getString("gateway_payment_id"));
                    m.put("amountMinor", rs.getLong("amount_minor"));
                    m.put("amountRefundedMinor", rs.getLong("amount_refunded_minor"));
                    m.put("currency", rs.getString("currency"));
                    m.put("status", rs.getString("status"));
                    m.put("gatewayVersion", rs.getLong("gateway_version"));
                    m.put("orderStatus", rs.getString("order_status"));
                    m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                    m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
                    return m;
                })
                .list();
    }

    /** Everything known about one payment, merged into one time-ordered lifecycle. */
    @GetMapping("/api/payments/{paymentId}/timeline")
    public Map<String, Object> timeline(@PathVariable UUID paymentId) {
        List<Map<String, Object>> rows = payments(null, paymentId.toString(), 1);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Payment", paymentId);
        }
        Map<String, Object> payment = rows.get(0);
        String gp = (String) payment.get("gatewayPaymentId");

        Object gatewayView;
        try {
            gatewayView = gp == null ? null : gateway.getPayment(gp);
        } catch (ApiException e) {
            gatewayView = Map.of("error", e.getMessage());
        }

        List<Map<String, Object>> events = new ArrayList<>();
        add(events, (OffsetDateTime) payment.get("createdAt"), "merchant", "PAYMENT_CREATED", "checkout created payment " + paymentId);
        if (gp != null) {
            jdbc.sql("""
                    SELECT received_at, processed_at, provider_event_id, event_type, gateway_payment_version, processing_status,
                           duplicate_count, processing_result
                      FROM webhook_events WHERE gateway_payment_id = :gp ORDER BY id
                    """).param("gp", gp).query((rs, n) -> {
                add(events, rs.getObject("received_at", OffsetDateTime.class), "webhook", "RECEIVED",
                        rs.getString("event_type") + " v" + rs.getLong("gateway_payment_version") + " (" + rs.getString("provider_event_id")
                                + ")" + (rs.getInt("duplicate_count") > 0 ? ", " + rs.getInt("duplicate_count") + " duplicate deliveries" : ""));
                OffsetDateTime processed = rs.getObject("processed_at", OffsetDateTime.class);
                if (processed != null) {
                    add(events, processed, "worker", rs.getString("processing_status"),
                            rs.getString("event_type") + " -> " + rs.getString("processing_result"));
                }
                return null;
            }).list();
            jdbc.sql("""
                    SELECT a.finished_at, a.outcome, a.attempt_no, a.worker_id, a.redelivered, a.error_class, w.event_type
                      FROM processing_attempts a JOIN webhook_events w ON w.id = a.webhook_event_id
                     WHERE w.gateway_payment_id = :gp AND a.outcome <> 'SUCCESS' ORDER BY a.id
                    """).param("gp", gp).query((rs, n) -> {
                add(events, rs.getObject("finished_at", OffsetDateTime.class), "worker", rs.getString("outcome"),
                        rs.getString("event_type") + " attempt #" + rs.getInt("attempt_no")
                                + (rs.getBoolean("redelivered") ? " (redelivered)" : "")
                                + (rs.getString("error_class") != null ? " - " + rs.getString("error_class") : ""));
                return null;
            }).list();
            jdbc.sql("SELECT created_at, mismatch_type, resolution, run_id FROM reconciliation_mismatches WHERE gateway_payment_id = :gp ORDER BY id")
                    .param("gp", gp).query((rs, n) -> {
                        add(events, rs.getObject("created_at", OffsetDateTime.class), "reconciliation", rs.getString("mismatch_type"),
                                "run #" + rs.getLong("run_id") + " -> " + rs.getString("resolution"));
                        return null;
                    }).list();
        }
        jdbc.sql("SELECT created_at, trigger_source, trigger_ref FROM fulfilments WHERE payment_id = :p")
                .param("p", paymentId).query((rs, n) -> {
                    add(events, rs.getObject("created_at", OffsetDateTime.class), "fulfilment", "FULFILLED",
                            "shipped via " + rs.getString("trigger_source") + " (" + rs.getString("trigger_ref") + ")");
                    return null;
                }).list();
        events.sort(Comparator.comparing(e -> (OffsetDateTime) e.get("at")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payment", payment);
        out.put("gateway", gatewayView);
        out.put("timeline", events);
        out.put("audit", audit.recent("payment", paymentId.toString(), 50));
        return out;
    }

    // ------------------------------------------------------------------ audit

    @GetMapping("/api/audit")
    public List<AuditLog.Entry> auditLog(@RequestParam(required = false) String targetType,
                                         @RequestParam(required = false) String targetId,
                                         @RequestParam(defaultValue = "100") int limit) {
        return audit.recent(targetType, targetId, Math.max(1, Math.min(limit, 500)));
    }

    // ------------------------------------------------------------------ helpers

    private static void add(List<Map<String, Object>> list, OffsetDateTime at, String source, String kind, String detail) {
        if (at == null) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at);
        e.put("source", source);
        e.put("kind", kind);
        e.put("detail", detail);
        list.add(e);
    }

    private Map<String, Long> groupCount(String sql) {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.sql(sql).query((rs, n) -> m.put(rs.getString(1), rs.getLong(2))).list();
        return m;
    }

    private long scalar(String sql) {
        Long v = jdbc.sql(sql).query(Long.class).single();
        return v == null ? 0 : v;
    }
}
