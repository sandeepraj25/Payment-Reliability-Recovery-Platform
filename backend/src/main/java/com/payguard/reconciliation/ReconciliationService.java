package com.payguard.reconciliation;

import com.payguard.audit.AuditLog;
import com.payguard.common.error.ApiException;
import com.payguard.domain.payment.PaymentStatus;
import com.payguard.merchant.FulfilmentService;
import com.payguard.merchant.GatewayStateApplier;
import com.payguard.merchant.Payment;
import com.payguard.merchant.PaymentRepository;
import com.payguard.merchant.checkout.GatewayClient;
import com.payguard.webhook.verification.PayGuardSimVerifier;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Module G: compares merchant DB vs gateway (authoritative) vs settlement reports, then
 * <ul>
 *   <li>AUTO_RECOVER findings: re-checked UNDER A ROW LOCK and applied through the same
 *       {@link GatewayStateApplier} the webhook worker uses (version + reachability + UNIQUE fulfilment)</li>
 *   <li>MANUAL_REVIEW findings: stored OPEN, never touched automatically</li>
 *   <li>INFORMATIONAL findings: stored as evidence</li>
 * </ul>
 * The comparison may be seconds old by the time we recover. That is why recovery re-reads the
 * payment with SELECT ... FOR UPDATE and lets the applier decide again: if a webhook fixed it in
 * the meantime the result is STALE -> ALREADY_CONSISTENT, and nothing is applied twice.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationStore store;
    private final GatewayClient gateway;
    private final PaymentRepository payments;
    private final GatewayStateApplier applier;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final ReconciliationProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public ReconciliationService(ReconciliationStore store, GatewayClient gateway, PaymentRepository payments,
                                 GatewayStateApplier applier, AuditLog audit, TransactionTemplate tx,
                                 ReconciliationProperties props, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.gateway = gateway;
        this.payments = payments;
        this.applier = applier;
        this.audit = audit;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
        Gauge.builder("payguard.reconciliation.open.mismatches", store, ReconciliationStore::openCount)
                .description("mismatches waiting for manual review").register(meters);
    }

    public ReconciliationStore.Run run(String triggerType, String actor) {
        Instant now = clock.instant();
        Instant from = now.minus(props.lookback());
        store.abandonStaleRuns(now.minus(props.staleRunAfter()));
        long runId;
        try {
            runId = store.startRun(triggerType, actor, from, now);
        } catch (DuplicateKeyException alreadyRunning) {
            throw ApiException.conflict("RECONCILIATION_ALREADY_RUNNING", "Another reconciliation run is in progress");
        }
        log.info("reconciliation run {} started trigger={} by={} window={}..{}", runId, triggerType, actor, from, now);

        try {
            // 1) authoritative gateway view + settlement reports (HTTP, outside any DB transaction)
            List<GatewayClient.GatewayPaymentStatus> gw = gateway.listPaymentsUpdatedSince(from);
            for (GatewayClient.SettlementBatch b : gateway.settlementReportsSince(from)) {
                tx.executeWithoutResult(s -> store.importSettlementBatch(b));
            }
            // 2) merchant view
            List<ReconciliationRules.MerchantPayment> merchant = store.merchantPayments(from);

            Map<String, ReconciliationRules.GatewayPayment> gwById = new HashMap<>();
            List<ReconciliationRules.GatewayPayment> gwList = new ArrayList<>();
            for (GatewayClient.GatewayPaymentStatus g : gw) {
                var gp = new ReconciliationRules.GatewayPayment(g.id(), g.merchantOrderRef(), g.amountMinor(),
                        g.amountRefundedMinor(), g.currency(), PaymentStatus.valueOf(g.status()), g.version(), g.capturedAt());
                gwById.put(gp.id(), gp);
                gwList.add(gp);
            }

            // 3) compare (pure function)
            List<ReconciliationRules.Finding> findings = new ArrayList<>(ReconciliationRules.compare(merchant, gwList,
                    store.settlementsByGatewayPayment(), store.latestSettlementCutoff().orElse(null), now,
                    new ReconciliationRules.Settings(props.settlementExpectedWithin(), java.time.Duration.ofMinutes(15))));
            findings.addAll(eventFindings(from));

            // 4) act on each finding
            int auto = 0, manual = 0;
            for (ReconciliationRules.Finding f : findings) {
                String resolution = handle(runId, f, gwById.get(f.gatewayPaymentId()));
                if ("AUTO_RESOLVED".equals(resolution)) auto++;
                if ("OPEN".equals(resolution)) manual++;
                meters.counter("payguard.reconciliation.mismatches", "type", f.type().name()).increment();
            }
            store.finishRun(runId, merchant.size(), gwList.size(), findings.size(), auto, manual);
            log.info("reconciliation run {} completed: {} findings, {} auto-resolved, {} for manual review",
                    runId, findings.size(), auto, manual);
        } catch (RuntimeException e) {
            store.failRun(runId, e.toString());
            log.error("reconciliation run {} failed: {}", runId, e.toString());
            throw e;
        }
        return store.run(runId).orElseThrow();
    }

    private List<ReconciliationRules.Finding> eventFindings(Instant from) {
        List<ReconciliationRules.Finding> out = new ArrayList<>();
        for (ReconciliationStore.EventFinding e : store.noisyOrDeadEvents(from, props.duplicateThreshold())) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("providerEventId", e.providerEventId());
            ev.put("webhookEventId", e.id());
            if ("DEAD".equals(e.status())) {
                ev.put("lastError", e.lastError());
                out.add(new ReconciliationRules.Finding(MismatchType.DEAD_LETTERED_EVENT, e.gatewayPaymentId(), null, null,
                        "DEAD", "replay via /api/dead-letters/" + e.id() + "/replay", ev));
            }
            if (e.duplicateCount() >= props.duplicateThreshold()) {
                ev.put("duplicateCount", e.duplicateCount());
                out.add(new ReconciliationRules.Finding(MismatchType.DUPLICATE_DELIVERIES, e.gatewayPaymentId(), null, null,
                        e.duplicateCount() + " duplicates", "stored once", new LinkedHashMap<>(ev)));
            }
        }
        return out;
    }

    /** Returns the resolution stored for this finding. */
    private String handle(long runId, ReconciliationRules.Finding f, ReconciliationRules.GatewayPayment g) {
        return switch (f.type().handling()) {
            case INFORMATIONAL -> {
                tx.executeWithoutResult(s -> store.insertMismatch(runId, f, "INFORMATIONAL", null, null));
                yield "INFORMATIONAL";
            }
            case MANUAL_REVIEW -> {
                tx.executeWithoutResult(s -> store.insertMismatch(runId, f, "OPEN", null, null));
                yield "OPEN";
            }
            case AUTO_RECOVER -> tx.execute(s -> recover(runId, f, g));
        };
    }

    /** Recovery transaction: lock, re-decide, apply, audit, record - all or nothing. */
    private String recover(long runId, ReconciliationRules.Finding f, ReconciliationRules.GatewayPayment g) {
        Optional<Payment> locked = payments.findByGatewayIdForUpdate(PayGuardSimVerifier.PROVIDER, f.gatewayPaymentId());
        if (locked.isEmpty() || g == null) {
            store.insertMismatch(runId, f, "OPEN", "payment disappeared before recovery", null);
            return "OPEN";
        }
        Payment p = locked.get();
        PaymentStatus before = p.getStatus();
        long versionBefore = p.getGatewayVersion();
        GatewayStateApplier.Result r = applier.apply(p,
                new GatewayStateApplier.GatewayState(g.status(), g.version(), g.amountMinor(), g.currency(), g.amountRefundedMinor()),
                FulfilmentService.Source.RECONCILIATION, "recon-run-" + runId, clock.instant());

        return switch (r.outcome()) {
            case APPLIED, NO_CHANGE -> {
                String note = "applied gateway state " + g.status() + " v" + g.version() + " (was " + before + " v" + versionBefore + ")"
                        + (r.fulfilment() != null ? ", fulfilment: " + r.fulfilment() : "");
                audit.record("system:reconciliation", "RECONCILIATION_RECOVERY", "payment", p.getId().toString(), note,
                        Map.of("status", before.name(), "gatewayVersion", versionBefore),
                        Map.of("status", g.status().name(), "gatewayVersion", g.version(), "runId", runId));
                store.insertMismatch(runId, f, "AUTO_RESOLVED", note, "system:reconciliation");
                log.warn("reconciliation recovered payment {}: {}", p.getId(), note);
                yield "AUTO_RESOLVED";
            }
            case STALE -> {
                store.insertMismatch(runId, f, "ALREADY_CONSISTENT", "a webhook applied v" + versionBefore + " first", "system:reconciliation");
                yield "ALREADY_CONSISTENT";
            }
            // The world changed between comparison and recovery in a way that is no longer a safe forward step.
            case AMOUNT_MISMATCH, UNREACHABLE -> {
                store.insertMismatch(runId, f, "OPEN", "not safe to auto-apply at recovery time: " + r.outcome(), null);
                yield "OPEN";
            }
        };
    }

    public void resolveManually(long mismatchId, String actor, String note) {
        if (note == null || note.isBlank()) {
            throw ApiException.unprocessable("REASON_REQUIRED", "A resolution note is required (it is audited)");
        }
        tx.executeWithoutResult(s -> {
            if (!store.resolveManually(mismatchId, actor, note)) {
                throw ApiException.conflict("NOT_OPEN", "Mismatch " + mismatchId + " is not OPEN");
            }
            audit.record(actor, "MISMATCH_RESOLVED", "reconciliation_mismatch", Long.toString(mismatchId), note,
                    Map.of("resolution", "OPEN"), Map.of("resolution", "RESOLVED_MANUALLY"));
        });
    }
}
