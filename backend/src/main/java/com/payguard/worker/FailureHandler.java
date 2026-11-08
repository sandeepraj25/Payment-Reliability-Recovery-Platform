package com.payguard.worker;

import com.payguard.audit.AuditLog;
import com.payguard.outbox.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Module F: decides what happens after a failed attempt. Runs in its OWN transaction,
 * after the processing transaction has already rolled back.
 *
 * <ul>
 *   <li>transient + budget left -> attempt row, status RETRY_SCHEDULED, NEW outbox row due at now+backoff</li>
 *   <li>permanent, or budget used up -> attempt row, status DEAD, audit entry; the listener then
 *       rejects the message so RabbitMQ moves it to the dead-letter queue</li>
 * </ul>
 * The retry is scheduled through the outbox, so it inherits all of the outbox's guarantees.
 */
@Service
public class FailureHandler {

    private static final Logger log = LoggerFactory.getLogger(FailureHandler.class);

    public enum Decision { RETRY_SCHEDULED, DEAD_LETTERED, NOTHING_TO_DO }

    private final ProcessingStore store;
    private final OutboxStore outbox;
    private final AuditLog audit;
    private final FailureClassifier classifier;
    private final RetryPolicy policy;
    private final Clock clock;
    private final RandomGenerator random = RandomGenerator.getDefault();

    public FailureHandler(ProcessingStore store, OutboxStore outbox, AuditLog audit, FailureClassifier classifier,
                          WorkerProperties props, Clock clock) {
        this.store = store;
        this.outbox = outbox;
        this.audit = audit;
        this.classifier = classifier;
        this.policy = props.retryPolicy();
        this.clock = clock;
    }

    @Transactional
    public Decision handle(long webhookEventId, Throwable error, String workerId, boolean redelivered, Instant startedAt) {
        var locked = store.lockEvent(webhookEventId);
        if (locked.isEmpty()) {
            log.error("failure for unknown webhook event {} - message will be dropped: {}", webhookEventId, error.toString());
            return Decision.NOTHING_TO_DO;
        }
        ProcessingStore.LockedEvent e = locked.get();
        if (!e.status().equals("RECEIVED") && !e.status().equals("RETRY_SCHEDULED")) {
            // Already PROCESSED / DEAD: the business outcome is settled, so retrying is pointless - but an
            // error on this path is still a bug worth seeing (this is how a too-short column was found).
            log.error("error while handling already-{} event {} (no retry): {}", e.status(), webhookEventId, error.toString());
            return Decision.NOTHING_TO_DO;
        }

        FailureClassifier.Kind kind = classifier.classify(error);
        String code = classifier.code(error);
        String message = String.valueOf(error.getMessage());
        int attemptsInCycle = e.cycleAttempts() + 1;
        Instant now = clock.instant();

        if (kind == FailureClassifier.Kind.TRANSIENT && policy.shouldRetry(attemptsInCycle)) {
            Duration delay = policy.delayAfter(attemptsInCycle, random);
            store.recordAttempt(e, workerId, redelivered,
                    new ProcessingStore.Outcome("TRANSIENT_FAILURE", code, message, delay.toMillis()), startedAt, now, true);
            store.markRetryScheduled(e.id(), code + ": " + message);
            outbox.insert(e.id(), OutboxStore.Purpose.RETRY, e.providerEventId(), e.eventType(), e.gatewayPaymentId(),
                    now.plus(delay));
            log.warn("retry scheduled eventId={} attempt={}/{} delayMs={} error={}",
                    e.providerEventId(), attemptsInCycle, policy.maxAttempts(), delay.toMillis(), code);
            return Decision.RETRY_SCHEDULED;
        }

        String why = kind == FailureClassifier.Kind.PERMANENT
                ? "permanent failure " + code
                : "retries exhausted after " + attemptsInCycle + " attempts, last error " + code;
        store.recordAttempt(e, workerId, redelivered, new ProcessingStore.Outcome(
                kind == FailureClassifier.Kind.PERMANENT ? "PERMANENT_FAILURE" : "TRANSIENT_FAILURE", code, message, null),
                startedAt, now, true);
        store.markDead(e.id(), why + ": " + message);
        audit.record("system:worker", "DEAD_LETTERED", "webhook_event", Long.toString(e.id()), why,
                Map.of("status", e.status()), Map.of("status", "DEAD", "error", code));
        log.error("event dead-lettered eventId={} reason={} error={}", e.providerEventId(), why, message);
        return Decision.DEAD_LETTERED;
    }
}
