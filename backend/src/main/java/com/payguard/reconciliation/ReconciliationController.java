package com.payguard.reconciliation;

import com.payguard.common.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
public class ReconciliationController {

    private final ReconciliationService service;
    private final ReconciliationStore store;

    public ReconciliationController(ReconciliationService service, ReconciliationStore store) {
        this.service = service;
        this.store = store;
    }

    public record ResolveRequest(@NotBlank @Size(max = 500) String note) {
    }

    @PostMapping("/api/reconciliation/runs")
    public ReconciliationStore.Run start(Principal principal) {
        return service.run("MANUAL", principal.getName());
    }

    @GetMapping("/api/reconciliation/runs")
    public List<ReconciliationStore.Run> runs(@RequestParam(defaultValue = "20") int limit) {
        return store.runs(Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/api/reconciliation/runs/{id}")
    public ReconciliationStore.Run run(@PathVariable long id) {
        return store.run(id).orElseThrow(() -> ApiException.notFound("Reconciliation run", id));
    }

    @GetMapping("/api/reconciliation/mismatches")
    public List<ReconciliationStore.Mismatch> mismatches(@RequestParam(required = false) Long runId,
                                                         @RequestParam(required = false) String resolution,
                                                         @RequestParam(defaultValue = "200") int limit) {
        return store.mismatches(runId, resolution, Math.max(1, Math.min(limit, 1000)));
    }

    @PostMapping("/api/reconciliation/mismatches/{id}/resolve")
    public void resolve(@PathVariable long id, @Valid @RequestBody ResolveRequest request, Principal principal) {
        service.resolveManually(id, principal.getName(), request.note());
    }

    /** "Export report": CSV that opens in Excel/Sheets. Values are quoted; quotes are doubled (RFC 4180). */
    @GetMapping("/api/reconciliation/runs/{id}/report.csv")
    public ResponseEntity<String> report(@PathVariable long id) {
        ReconciliationStore.Run run = run(id);
        StringBuilder csv = new StringBuilder();
        csv.append("run_id,status,window_from,window_to,merchant_payments,gateway_payments,found,auto_resolved,manual_review\n");
        csv.append(String.join(",", q(run.id()), q(run.status()), q(run.windowFrom()), q(run.windowTo()),
                q(run.merchantPayments()), q(run.gatewayPayments()), q(run.mismatchesFound()), q(run.autoResolved()),
                q(run.manualReview()))).append("\n\n");
        csv.append("mismatch_id,type,severity,resolution,gateway_payment_id,merchant_payment_id,order_id,merchant_value,gateway_value,note\n");
        for (ReconciliationStore.Mismatch m : store.mismatches(id, null, 100_000)) {
            csv.append(String.join(",", q(m.id()), q(m.mismatchType()), q(m.severity()), q(m.resolution()),
                    q(m.gatewayPaymentId()), q(m.merchantPaymentId()), q(m.orderId()), q(m.merchantValue()),
                    q(m.gatewayValue()), q(m.resolutionNote()))).append("\n");
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reconciliation-run-" + id + ".csv")
                .body(csv.toString());
    }

    private static String q(Object v) {
        if (v == null) return "";
        String s = v.toString();
        // Leading = + - @ would be executed as a formula by spreadsheet apps (CSV injection).
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Configurable interval: payguard.reconciliation.interval (default 5 minutes). */
    @Component
    @ConditionalOnProperty(prefix = "payguard.reconciliation", name = "scheduled", havingValue = "true", matchIfMissing = true)
    static class Scheduler {
        private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
        private final ReconciliationService service;

        Scheduler(ReconciliationService service) {
            this.service = service;
        }

        @Scheduled(fixedDelayString = "${payguard.reconciliation.interval:PT5M}",
                initialDelayString = "${payguard.reconciliation.initial-delay:PT1M}")
        void scheduledRun() {
            try {
                service.run("SCHEDULED", "system:scheduler");
            } catch (ApiException e) {
                log.info("scheduled reconciliation skipped: {}", e.getMessage());
            } catch (RuntimeException e) {
                log.error("scheduled reconciliation failed: {}", e.toString());
            }
        }
    }
}
