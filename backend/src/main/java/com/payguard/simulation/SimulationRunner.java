package com.payguard.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.common.error.ApiException;
import com.payguard.faults.FaultInjector;
import com.payguard.faults.FaultPoint;
import com.payguard.gateway.GatewayEventStore;
import com.payguard.gateway.GatewayPayment;
import com.payguard.gateway.GatewayPaymentService;
import com.payguard.gateway.GatewayWebhookSender;
import com.payguard.merchant.checkout.CheckoutService;
import com.payguard.reconciliation.ReconciliationService;
import com.payguard.reconciliation.ReconciliationStore;
import com.payguard.webhook.signature.WebhookSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * Module H: runs a failure scenario against the REAL system and records what happened.
 *
 * <p>Every scenario follows the same five beats: initial state -> injected failure -> observed
 * (broken or incomplete) state -> recovery mechanism -> final verified state. Each beat stores
 * evidence read from the database at that moment. Nothing is scripted on the frontend; if the
 * backend misbehaved, the run is FAILED and the evidence shows why.
 *
 * <p>Only exists when both the gateway simulator and fault injection are enabled (local/demo).
 */
@Service
@ConditionalOnProperty(prefix = "payguard.faults", name = "enabled", havingValue = "true")
public class SimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunner.class);
    private static final long AMOUNT = 499900; // Rs. 4,999.00
    private static final Duration WAIT = Duration.ofSeconds(30);

    private final SimulationRecorder recorder;
    private final FaultInjector faults;
    private final CheckoutService checkout;
    private final GatewayPaymentService gateway;
    private final GatewayWebhookSender sender;
    private final GatewayEventStore gatewayEvents;
    private final ReconciliationService reconciliation;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final RestClient http;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "simulation-runner");
        t.setDaemon(true);
        return t;
    });

    public SimulationRunner(SimulationRecorder recorder, FaultInjector faults, CheckoutService checkout,
                            GatewayPaymentService gateway, GatewayWebhookSender sender, GatewayEventStore gatewayEvents,
                            ReconciliationService reconciliation, JdbcClient jdbc, ObjectMapper objectMapper,
                            Environment environment, RestClient.Builder builder) {
        this.recorder = recorder;
        this.faults = faults;
        this.checkout = checkout;
        this.gateway = gateway;
        this.sender = sender;
        this.gatewayEvents = gatewayEvents;
        this.reconciliation = reconciliation;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.http = builder.build();
    }

    /** Starts the scenario in the background (one at a time) and returns the run id to poll. */
    public long start(Scenario scenario, String actor) {
        long runId = recorder.start(scenario, actor);
        executor.submit(() -> execute(runId, scenario));
        return runId;
    }

    // =====================================================================  execution frame

    private final class Ctx {
        final long runId;
        final Instant t0 = Instant.now();
        final List<SimulationRecorder.Invariant> invariants = new ArrayList<>();
        String orderId;
        String paymentId;
        String gatewayPaymentId;

        Ctx(long runId) {
            this.runId = runId;
        }

        void step(String phase, String title, String detail, Map<String, Object> evidence) {
            Instant now = Instant.now();
            recorder.appendStep(runId, new SimulationRecorder.Step(phase, title, detail, evidence, now,
                    Duration.between(t0, now).toMillis()));
        }

        void check(String name, boolean held, String detail) {
            invariants.add(new SimulationRecorder.Invariant(name, held, detail));
        }
    }

    private void execute(long runId, Scenario scenario) {
        Ctx c = new Ctx(runId);
        String error = null;
        try {
            switch (scenario) {
                case DUPLICATE_WEBHOOK -> duplicateWebhook(c);
                case DELAYED_WEBHOOK -> delayedWebhook(c);
                case OUT_OF_ORDER_EVENTS -> outOfOrder(c);
                case WORKER_CRASH_BEFORE_COMMIT -> workerCrash(c, FaultPoint.WORKER_CRASH_BEFORE_COMMIT);
                case WORKER_CRASH_AFTER_COMMIT -> workerCrash(c, FaultPoint.WORKER_CRASH_AFTER_COMMIT);
                case DATABASE_UNAVAILABLE -> databaseUnavailable(c);
                case RABBITMQ_UNAVAILABLE -> brokerUnavailable(c);
                case OUTBOX_PUBLISHER_CRASH -> publisherCrash(c);
                case MISSING_WEBHOOK -> missingWebhook(c);
                case CONCURRENT_DUPLICATE_REQUESTS -> concurrentRequests(c);
                case GATEWAY_API_TIMEOUT -> gatewayTimeout(c);
                case INVALID_SIGNATURE -> invalidSignature(c);
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("simulation {} {} errored", runId, scenario, e);
            try {
                c.step("ERROR", "Simulation could not complete", error, snapshot(c));
            } catch (RuntimeException ignored) {
                // the database itself may be the problem
            }
        } finally {
            recorder.finish(runId, c.invariants, error);
        }
    }

    // =====================================================================  scenarios

    private void duplicateWebhook(Ctx c) {
        newOrder(c, "dup");
        c.step("INITIAL", "Order placed, payment captured at the gateway", "checkout -> authorize -> capture", snapshot(c));
        gateway.authorize(c.gatewayPaymentId);
        gateway.capture(c.gatewayPaymentId);
        waitFor(() -> "FULFILLED".equals(orderStatus(c)), "order fulfilled");
        c.step("INITIAL", "Webhooks processed once, order fulfilled", "", snapshot(c));

        String captured = eventId(c, "payment.captured");
        for (int i = 0; i < 5; i++) {
            sender.deliver(captured, "MANUAL_REDELIVERY");
        }
        c.step("INJECT", "Gateway re-delivers the captured event 5 times", "event " + captured, snapshot(c));
        sleep(1500);
        c.step("OBSERVED", "All 5 deliveries acknowledged with 200 DUPLICATE", "they hit the unique key", snapshot(c));
        c.step("RECOVERY", "Nothing to recover: dedup happened at ingestion", Scenario.DUPLICATE_WEBHOOK.mechanisms(), Map.of(
                "duplicateCount", scalar("SELECT duplicate_count FROM webhook_events WHERE provider_event_id = :e", Map.of("e", captured)),
                "outboxRowsForEvent", scalar("SELECT count(*) FROM outbox_events o JOIN webhook_events w ON w.id = o.webhook_event_id WHERE w.provider_event_id = :e", Map.of("e", captured))));
        finalChecks(c);
        c.check("event stored once", count("SELECT count(*) FROM webhook_events WHERE provider_event_id = :e", Map.of("e", captured)) == 1, "");
        c.check("5 duplicates counted", count("SELECT duplicate_count FROM webhook_events WHERE provider_event_id = :e", Map.of("e", captured)) == 5, "");
    }

    private void delayedWebhook(Ctx c) {
        newOrder(c, "delay");
        gateway.authorize(c.gatewayPaymentId);
        waitFor(() -> "AUTHORIZED".equals(paymentStatus(c)), "authorized applied");
        c.step("INITIAL", "Payment authorized and known to the merchant", "", snapshot(c));
        faults.arm(FaultPoint.GATEWAY_DELAY_WEBHOOK, 1, "simulation");
        gateway.capture(c.gatewayPaymentId);
        c.step("INJECT", "Gateway captured, but holds the webhook back", "GATEWAY_DELAY_WEBHOOK armed", snapshot(c));
        sleep(500);
        c.step("OBSERVED", "Customer paid, merchant still PAYMENT_PENDING", "temporary, expected inconsistency", snapshot(c));
        c.check("merchant waited (did not guess)", "PAYMENT_PENDING".equals(orderStatus(c)), "order status while webhook delayed");
        waitFor(() -> "FULFILLED".equals(orderStatus(c)), "delayed webhook processed");
        c.step("RECOVERY", "Delayed webhook arrived and was processed normally", Scenario.DELAYED_WEBHOOK.mechanisms(), snapshot(c));
        finalChecks(c);
    }

    private void outOfOrder(Ctx c) {
        newOrder(c, "ooo");
        faults.arm(FaultPoint.GATEWAY_DROP_WEBHOOK, 2, "simulation");
        gateway.authorize(c.gatewayPaymentId);
        gateway.capture(c.gatewayPaymentId);
        c.step("INITIAL", "Gateway authorized (v1) and captured (v2)", "automatic webhooks held back", snapshot(c));
        String authorized = eventId(c, "payment.authorized");
        String captured = eventId(c, "payment.captured");

        sender.deliver(captured, "MANUAL_REDELIVERY");
        c.step("INJECT", "v2 'captured' delivered FIRST", captured, snapshot(c));
        waitFor(() -> "FULFILLED".equals(orderStatus(c)), "captured applied");
        sender.deliver(authorized, "MANUAL_REDELIVERY");
        c.step("OBSERVED", "Older v1 'authorized' arrives AFTER v2", authorized, snapshot(c));
        waitFor(() -> "PROCESSED".equals(eventStatus(authorized)), "authorized processed");
        c.step("RECOVERY", "Stale event ignored, no regression", Scenario.OUT_OF_ORDER_EVENTS.mechanisms(), Map.of(
                "authorizedAttemptOutcomes", attemptOutcomes(authorized)));
        finalChecks(c);
        c.check("payment not regressed", "CAPTURED".equals(paymentStatus(c)), "status " + paymentStatus(c));
        c.check("stale event recorded as SKIPPED_STALE", attemptOutcomes(authorized).contains("SKIPPED_STALE"), "");
    }

    private void workerCrash(Ctx c, FaultPoint point) {
        newOrder(c, "crash");
        c.step("INITIAL", "Order placed", "", snapshot(c));
        faults.arm(point, 1, "simulation");
        gateway.authorize(c.gatewayPaymentId);
        gateway.capture(c.gatewayPaymentId);
        c.step("INJECT", "Worker armed to crash " + (point == FaultPoint.WORKER_CRASH_BEFORE_COMMIT
                ? "just before COMMIT" : "after COMMIT, before ACK"), point.name(), snapshot(c));
        waitFor(() -> "FULFILLED".equals(orderStatus(c)), "order fulfilled after redelivery");
        String captured = eventId(c, "payment.captured");
        String authorized = eventId(c, "payment.authorized");
        c.step("OBSERVED", "One message was never acked; RabbitMQ redelivered it", "", Map.of(
                "attempts(authorized)", attemptOutcomes(authorized), "attempts(captured)", attemptOutcomes(captured),
                "redeliveredAttempts", count("SELECT count(*) FROM processing_attempts a JOIN webhook_events w ON w.id = a.webhook_event_id WHERE w.gateway_payment_id = :g AND a.redelivered", Map.of("g", c.gatewayPaymentId))));
        c.step("RECOVERY", point == FaultPoint.WORKER_CRASH_BEFORE_COMMIT ? "Rollback + redelivery" : "Redelivery skipped as already processed",
                (point == FaultPoint.WORKER_CRASH_BEFORE_COMMIT ? Scenario.WORKER_CRASH_BEFORE_COMMIT : Scenario.WORKER_CRASH_AFTER_COMMIT).mechanisms(), snapshot(c));
        finalChecks(c);
        c.check("redelivery observed", count("SELECT count(*) FROM processing_attempts a JOIN webhook_events w ON w.id = a.webhook_event_id WHERE w.gateway_payment_id = :g AND a.redelivered", Map.of("g", c.gatewayPaymentId)) >= 1, "");
    }

    private void databaseUnavailable(Ctx c) {
        newOrder(c, "db");
        gateway.authorize(c.gatewayPaymentId);
        waitFor(() -> "AUTHORIZED".equals(paymentStatus(c)), "authorized applied");
        c.step("INITIAL", "Payment authorized", "", snapshot(c));
        faults.arm(FaultPoint.INGESTION_DB_UNAVAILABLE, 1, "simulation");
        gateway.capture(c.gatewayPaymentId);
        String captured = eventId(c, "payment.captured");
        waitFor(() -> count("SELECT count(*) FROM gateway.webhook_deliveries WHERE event_id = :e", Map.of("e", captured)) >= 1, "delivery attempted");
        c.step("INJECT", "Database fails while the 'captured' webhook is stored", "INGESTION_DB_UNAVAILABLE", Map.of(
                "gatewayDeliveries", deliveries(captured)));
        c.step("OBSERVED", "Merchant answered 503: nothing stored, nothing acknowledged", "", snapshot(c));
        c.check("no false acknowledgement", count("SELECT count(*) FROM webhook_events WHERE provider_event_id = :e", Map.of("e", captured)) == 0,
                "event absent after the 503");
        sender.deliver(captured, "MANUAL_REDELIVERY");
        c.step("RECOVERY", "Gateway retries the delivery", Scenario.DATABASE_UNAVAILABLE.mechanisms(), Map.of("gatewayDeliveries", deliveries(captured)));
        waitFor(() -> "FULFILLED".equals(orderStatus(c)), "order fulfilled");
        finalChecks(c);
    }

    private void brokerUnavailable(Ctx c) {
        newOrder(c, "mq");
        c.step("INITIAL", "Order placed", "", snapshot(c));
        faults.arm(FaultPoint.OUTBOX_BROKER_UNAVAILABLE, 3, "simulation");
        gateway.authorize(c.gatewayPaymentId);
        c.step("INJECT", "RabbitMQ unreachable for the next 3 publish attempts", "OUTBOX_BROKER_UNAVAILABLE x3", snapshot(c));
        sleep(1200);
        c.step("OBSERVED", "Webhook stored and acknowledged; outbox row waits with backoff", "", Map.of("outbox", outbox(c)));
        waitFor(() -> "AUTHORIZED".equals(paymentStatus(c)), "authorized applied");
        c.step("RECOVERY", "Broker back: outbox publishes, worker processes", Scenario.RABBITMQ_UNAVAILABLE.mechanisms(), Map.of("outbox", outbox(c)));
        gateway.capture(c.gatewayPaymentId);
        waitFor(() -> "FULFILLED".equals(orderStatus(c)), "order fulfilled");
        finalChecks(c);
        c.check("outbox retried with backoff",
                count("SELECT max(o.publish_attempts) FROM outbox_events o JOIN webhook_events w ON w.id = o.webhook_event_id WHERE w.gateway_payment_id = :g", Map.of("g", c.gatewayPaymentId)) >= 2, "");
    }

    private void publisherCrash(Ctx c) {
        newOrder(c, "pub");
        c.step("INITIAL", "Order placed", "", snapshot(c));
        faults.arm(FaultPoint.OUTBOX_CRASH_AFTER_CONFIRM, 1, "simulation");
        gateway.authorize(c.gatewayPaymentId);
        gateway.capture(c.gatewayPaymentId);
        c.step("INJECT", "Publisher crashes after broker confirm, before marking PUBLISHED", "OUTBOX_CRASH_AFTER_CONFIRM", snapshot(c));
        waitFor(() -> "FULFILLED".equals(orderStatus(c)), "order fulfilled");
        waitFor(() -> attemptsForPayment(c).contains("SKIPPED_ALREADY_PROCESSED"), "duplicate message skipped");
        c.step("OBSERVED", "The same message was published twice", "", Map.of("attempts", attemptsForPayment(c), "outbox", outbox(c)));
        c.step("RECOVERY", "Worker deduplicated the second copy", Scenario.OUTBOX_PUBLISHER_CRASH.mechanisms(), snapshot(c));
        finalChecks(c);
    }

    private void missingWebhook(Ctx c) {
        newOrder(c, "missing");
        gateway.authorize(c.gatewayPaymentId);
        waitFor(() -> "AUTHORIZED".equals(paymentStatus(c)), "authorized applied");
        c.step("INITIAL", "Payment authorized", "", snapshot(c));
        faults.arm(FaultPoint.GATEWAY_DROP_WEBHOOK, 1, "simulation");
        gateway.capture(c.gatewayPaymentId);
        c.step("INJECT", "Gateway captures Rs. 4,999 but never sends the webhook", "GATEWAY_DROP_WEBHOOK", snapshot(c));
        sleep(1500);
        c.step("OBSERVED", "Customer paid, merchant shows PAYMENT_PENDING - nothing will ever fix this by itself", "", snapshot(c));
        c.check("problem reproduced", "PAYMENT_PENDING".equals(orderStatus(c)), "before reconciliation");
        ReconciliationStore.Run run = reconciliation.run("MANUAL", "simulation");
        c.step("RECOVERY", "Reconciliation run #" + run.id() + " compared with the gateway status API", Scenario.MISSING_WEBHOOK.mechanisms(), Map.of(
                "run", Map.of("id", run.id(), "found", run.mismatchesFound(), "autoResolved", run.autoResolved()),
                "mismatches", jdbc.sql("SELECT mismatch_type || ' -> ' || resolution FROM reconciliation_mismatches WHERE run_id = :r AND gateway_payment_id = :g")
                        .param("r", run.id()).param("g", c.gatewayPaymentId).query(String.class).list(),
                "fulfilmentSource", jdbc.sql("SELECT trigger_source FROM fulfilments WHERE order_id = CAST(:o AS uuid)").param("o", c.orderId).query(String.class).list()));
        finalChecks(c);
    }

    private void concurrentRequests(Ctx c) throws Exception {
        String key = "sim-conc-" + UUID.randomUUID();
        c.step("INITIAL", "No order yet", "key " + key, Map.of());
        ExecutorService pool = Executors.newFixedThreadPool(50);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            results.add(pool.submit(() -> {
                try {
                    return checkout.checkout(key, new CheckoutService.CheckoutCommand("sim_customer", AMOUNT, "INR")).status();
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        Map<Integer, Integer> byStatus = new java.util.TreeMap<>();
        for (Future<Integer> f : results) byStatus.merge(f.get(), 1, Integer::sum);
        pool.shutdown();
        c.step("INJECT", "50 identical checkout requests at once (same Idempotency-Key)", "", Map.of("httpStatuses", byStatus));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("idempotencyRecord", jdbc.sql("SELECT status || ' / ' || recovery_point FROM idempotency_records WHERE idempotency_key = :k").param("k", key).query(String.class).list());
        ev.put("ordersForKey", count("SELECT count(*) FROM payments p JOIN idempotency_records r ON r.resource_id = p.id::text WHERE r.idempotency_key = :k", Map.of("k", key)));
        c.step("OBSERVED", "Winners: 201; others: 409 in-progress or the stored 201 replay", "", ev);
        c.step("RECOVERY", "No recovery needed", Scenario.CONCURRENT_DUPLICATE_REQUESTS.mechanisms(), Map.of());
        c.check("exactly one order", count("SELECT count(*) FROM payments p JOIN idempotency_records r ON r.resource_id = p.id::text WHERE r.idempotency_key = :k", Map.of("k", key)) == 1, "");
        c.check("only 201/409 responses", byStatus.keySet().stream().allMatch(s -> s == 201 || s == 409), byStatus.toString());
    }

    private void gatewayTimeout(Ctx c) {
        String key = "sim-timeout-" + UUID.randomUUID();
        c.step("INITIAL", "No order yet", "key " + key, Map.of());
        faults.arm(FaultPoint.GATEWAY_API_TIMEOUT, 1, "simulation");
        int first;
        try {
            first = checkout.checkout(key, new CheckoutService.CheckoutCommand("sim_customer", AMOUNT, "INR")).status();
        } catch (ApiException e) {
            first = e.status().value();
        }
        String paymentId = jdbc.sql("SELECT resource_id FROM idempotency_records WHERE idempotency_key = :k").param("k", key).query(String.class).single();
        c.step("INJECT", "Gateway created the payment but answered too late", "merchant got HTTP " + first, Map.of(
                "gatewayPaymentsWithOurKey", count("SELECT count(*) FROM gateway.gateway_payments WHERE create_idempotency_key = :k", Map.of("k", "pgk_" + paymentId))));
        c.step("OBSERVED", "Ambiguous: the client does not know whether it was charged", "", Map.of());
        CheckoutService.HttpResult retry = checkout.checkout(key, new CheckoutService.CheckoutCommand("sim_customer", AMOUNT, "INR"));
        c.step("RECOVERY", "Client retries with the SAME Idempotency-Key", Scenario.GATEWAY_API_TIMEOUT.mechanisms(), Map.of(
                "retryStatus", retry.status(), "response", retry.body()));
        long gp = count("SELECT count(*) FROM gateway.gateway_payments WHERE create_idempotency_key = :k", Map.of("k", "pgk_" + paymentId));
        c.step("FINAL", "One order, one gateway payment", "", Map.of("gatewayPaymentsWithOurKey", gp));
        c.check("first call failed as a timeout", first == 503, "status " + first);
        c.check("exactly one gateway payment", gp == 1, "");
        c.check("retry succeeded", retry.status() == 201, "");
    }

    private void invalidSignature(Ctx c) {
        newOrder(c, "forge");
        c.step("INITIAL", "Order unpaid", "", snapshot(c));
        String body = """
                {"id":"evt_forged_%s","type":"payment.captured","created_at":"%s","data":{"payment":{"id":"%s","amount_minor":%d,"currency":"INR","status":"CAPTURED","version":99}}}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8), Instant.now(), c.gatewayPaymentId, AMOUNT);
        String forged = WebhookSignature.sign(Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8),
                "attacker-guessed-secret-123".getBytes(StandardCharsets.UTF_8));
        int status = http.post().uri(environment.getRequiredProperty("payguard.simulator.webhook-url"))
                .contentType(MediaType.APPLICATION_JSON).header(WebhookSignature.SIGNATURE_HEADER, forged).body(body)
                .exchange((req, res) -> res.getStatusCode().value());
        c.step("INJECT", "Forged 'captured' webhook posted with a wrong secret", "HTTP " + status, Map.of());
        c.step("OBSERVED", "Rejected before parsing", "", Map.of("latestRejection",
                jdbc.sql("SELECT reason || ' at ' || received_at FROM webhook_rejections ORDER BY id DESC LIMIT 1").query(String.class).list()));
        c.step("RECOVERY", "Nothing to recover: no state was touched", Scenario.INVALID_SIGNATURE.mechanisms(), snapshot(c));
        c.check("rejected with 401", status == 401, "HTTP " + status);
        c.check("order still unpaid", "PAYMENT_PENDING".equals(orderStatus(c)), "");
        c.check("no fulfilment", count("SELECT count(*) FROM fulfilments WHERE order_id = CAST(:o AS uuid)", Map.of("o", c.orderId)) == 0, "");
    }

    // =====================================================================  helpers

    private void newOrder(Ctx c, String tag) {
        CheckoutService.HttpResult r = checkout.checkout("sim-" + tag + "-" + UUID.randomUUID(),
                new CheckoutService.CheckoutCommand("sim_customer", AMOUNT, "INR"));
        try {
            JsonNode n = objectMapper.readTree(r.body());
            c.orderId = n.get("orderId").asText();
            c.paymentId = n.get("paymentId").asText();
            c.gatewayPaymentId = n.get("gatewayPaymentId").asText();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void finalChecks(Ctx c) {
        c.step("FINAL", "Final verified state", "", snapshot(c));
        long fulfilments = count("SELECT count(*) FROM fulfilments WHERE order_id = CAST(:o AS uuid)", Map.of("o", c.orderId));
        GatewayPayment gp = gateway.get(c.gatewayPaymentId);
        c.check("exactly one fulfilment", fulfilments == 1, fulfilments + " fulfilment row(s)");
        c.check("merchant matches gateway", gp.getStatus().name().equals(paymentStatus(c)),
                "merchant " + paymentStatus(c) + " / gateway " + gp.getStatus());
        c.check("order fulfilled", "FULFILLED".equals(orderStatus(c)), orderStatus(c));
    }

    /** The evidence block shown under each timeline step: straight from the database. */
    private Map<String, Object> snapshot(Ctx c) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (c.orderId == null) {
            return s;
        }
        s.put("order", jdbc.sql("SELECT status FROM orders WHERE id = CAST(:o AS uuid)").param("o", c.orderId).query(String.class).single());
        s.put("merchantPayment", jdbc.sql("SELECT status || ' (gateway v' || gateway_version || ')' FROM payments WHERE id = CAST(:p AS uuid)")
                .param("p", c.paymentId).query(String.class).single());
        GatewayPayment gp = gateway.get(c.gatewayPaymentId);
        s.put("gatewayPayment", gp.getStatus() + " (v" + gp.getVersion() + ")");
        s.put("webhookEvents", jdbc.sql("""
                SELECT event_type || ' v' || gateway_payment_version || ' ' || processing_status
                       || CASE WHEN duplicate_count > 0 THEN ' dup=' || duplicate_count ELSE '' END
                  FROM webhook_events WHERE gateway_payment_id = :g ORDER BY id
                """).param("g", c.gatewayPaymentId).query(String.class).list());
        s.put("processingAttempts", attemptsForPayment(c));
        s.put("fulfilments", count("SELECT count(*) FROM fulfilments WHERE order_id = CAST(:o AS uuid)", Map.of("o", c.orderId)));
        return s;
    }

    private List<String> attemptsForPayment(Ctx c) {
        return jdbc.sql("""
                SELECT w.event_type || ' #' || a.attempt_no || ' ' || a.outcome || CASE WHEN a.redelivered THEN ' (redelivered)' ELSE '' END
                  FROM processing_attempts a JOIN webhook_events w ON w.id = a.webhook_event_id
                 WHERE w.gateway_payment_id = :g ORDER BY a.id
                """).param("g", c.gatewayPaymentId).query(String.class).list();
    }

    private List<String> outbox(Ctx c) {
        return jdbc.sql("""
                SELECT o.purpose || ' ' || o.status || ' attempts=' || o.publish_attempts || COALESCE(' error=' || o.last_error, '')
                  FROM outbox_events o JOIN webhook_events w ON w.id = o.webhook_event_id
                 WHERE w.gateway_payment_id = :g ORDER BY o.id
                """).param("g", c.gatewayPaymentId).query(String.class).list();
    }

    private List<String> deliveries(String eventId) {
        return gatewayEvents.findDeliveries(eventId).stream()
                .map(d -> d.triggerType() + " -> " + (d.httpStatus() == null ? d.error() : "HTTP " + d.httpStatus())).toList();
    }

    private List<String> attemptOutcomes(String providerEventId) {
        return jdbc.sql("SELECT a.outcome FROM processing_attempts a JOIN webhook_events w ON w.id = a.webhook_event_id WHERE w.provider_event_id = :e ORDER BY a.attempt_no")
                .param("e", providerEventId).query(String.class).list();
    }

    private String eventId(Ctx c, String type) {
        return gatewayEvents.findEventsForPayment(c.gatewayPaymentId).stream().filter(e -> e.eventType().equals(type))
                .map(GatewayEventStore.StoredEvent::id).findFirst().orElseThrow();
    }

    private String orderStatus(Ctx c) {
        return jdbc.sql("SELECT status FROM orders WHERE id = CAST(:o AS uuid)").param("o", c.orderId).query(String.class).single();
    }

    private String paymentStatus(Ctx c) {
        return jdbc.sql("SELECT status FROM payments WHERE id = CAST(:p AS uuid)").param("p", c.paymentId).query(String.class).single();
    }

    private String eventStatus(String providerEventId) {
        return jdbc.sql("SELECT processing_status FROM webhook_events WHERE provider_event_id = :e").param("e", providerEventId)
                .query(String.class).optional().orElse("MISSING");
    }

    private long count(String sql, Map<String, ?> params) {
        var spec = jdbc.sql(sql);
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        Long v = spec.query(Long.class).single();
        return v == null ? 0 : v;
    }

    private Object scalar(String sql, Map<String, ?> params) {
        return count(sql, params);
    }

    private void waitFor(BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(200);
        }
        throw new IllegalStateException("timed out waiting for: " + what + " (is the worker process running?)");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }
}
