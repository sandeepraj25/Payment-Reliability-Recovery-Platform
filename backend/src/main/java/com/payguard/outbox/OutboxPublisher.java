package com.payguard.outbox;

import com.payguard.faults.FaultInjector;
import com.payguard.faults.FaultPoint;
import com.payguard.faults.SimulatedCrashException;
import com.payguard.messaging.RabbitTopology;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module D: moves committed outbox rows to RabbitMQ.
 *
 * <pre>
 *  BEGIN
 *    SELECT due PENDING rows ... FOR UPDATE SKIP LOCKED      (claim)
 *    publish all, wait for broker CONFIRMS                    (network, rows still locked)
 *    confirmed  -> UPDATE status = PUBLISHED
 *    not sure   -> UPDATE publish_attempts+1, next_attempt_at = backoff   (still PENDING)
 *  COMMIT
 * </pre>
 *
 * <b>Ambiguous outcomes.</b> If the broker confirmed but we crash before COMMIT, the rows stay
 * PENDING and are published AGAIN. That is a deliberate choice: duplicates are harmless because
 * the worker deduplicates; a lost event is not. "At-least-once publishing + idempotent consumer".
 *
 * <b>Trade-off.</b> Row locks are held during the publish (a few ms normally, up to the confirm
 * timeout when the broker hangs). Acceptable because only publishers touch these rows and
 * SKIP LOCKED means they never wait on each other.
 */
@Component
@ConditionalOnProperty(prefix = "payguard.outbox", name = "publisher-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore store;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final FaultInjector faults;
    private final Clock clock;
    private final int batchSize;
    private final long confirmTimeoutMs;
    private final Set<String> returnedMessageIds = ConcurrentHashMap.newKeySet();
    private final Counter published;
    private final Counter publishFailures;

    public OutboxPublisher(OutboxStore store, RabbitTemplate rabbit, TransactionTemplate tx, FaultInjector faults,
                           Clock clock, MeterRegistry meters,
                           @Value("${payguard.outbox.batch-size:50}") int batchSize,
                           @Value("${payguard.outbox.confirm-timeout:PT5S}") Duration confirmTimeout) {
        this.store = store;
        this.rabbit = rabbit;
        this.tx = tx;
        this.faults = faults;
        this.clock = clock;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeout.toMillis();
        // mandatory=true: a message that matches no queue comes back instead of vanishing silently.
        this.rabbit.setReturnsCallback(returned -> {
            String id = returned.getMessage().getMessageProperties().getMessageId();
            log.error("outbox message returned as unroutable messageId={} replyText={}", id, returned.getReplyText());
            if (id != null) {
                returnedMessageIds.add(id);
            }
        });
        this.published = Counter.builder("payguard.outbox.published").description("outbox rows confirmed by the broker").register(meters);
        this.publishFailures = Counter.builder("payguard.outbox.publish.failures").description("publish batches that failed or were ambiguous").register(meters);
        Gauge.builder("payguard.outbox.backlog", store, OutboxStore::pendingCount)
                .description("outbox rows not yet published").register(meters);
        Gauge.builder("payguard.outbox.oldest.due.age.seconds", store, s -> s.oldestDueAgeSeconds(clock.instant()))
                .description("age of the oldest due outbox row").register(meters);
    }

    @Scheduled(fixedDelayString = "${payguard.outbox.poll-interval-ms:500}")
    public void poll() {
        try {
            int n;
            do {
                n = publishOnce();
            } while (n == batchSize); // drain quickly when there is a backlog
        } catch (SimulatedCrashException e) {
            log.warn("outbox publisher crashed (simulated) at {} - claimed rows were rolled back to PENDING", e.point());
        } catch (RuntimeException e) {
            log.error("outbox poll failed (will retry next poll): {}", e.toString());
        }
    }

    /** One claim-publish-mark cycle in one transaction. Returns how many rows were claimed. */
    public int publishOnce() {
        Integer claimed = tx.execute(status -> {
            Instant now = clock.instant();
            List<OutboxStore.PendingRow> rows = store.claimDue(batchSize, now);
            if (rows.isEmpty()) {
                return 0;
            }
            List<Long> ids = rows.stream().map(OutboxStore.PendingRow::id).toList();
            try {
                if (faults.shouldFire(FaultPoint.OUTBOX_BROKER_UNAVAILABLE)) {
                    throw new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("simulated: broker unreachable"));
                }
                rabbit.invoke(ops -> {
                    for (OutboxStore.PendingRow row : rows) {
                        ops.send(RabbitTopology.EXCHANGE, row.routingKey(), toMessage(row));
                    }
                    // Blocks until the broker has persisted every message (publisher confirms), or throws.
                    ops.waitForConfirmsOrDie(confirmTimeoutMs);
                    return null;
                });
                List<String> returned = rows.stream().map(r -> messageId(r)).filter(returnedMessageIds::remove).toList();
                if (!returned.isEmpty()) {
                    throw new IllegalStateException("unroutable messages: " + returned);
                }
            } catch (RuntimeException e) {
                store.markPublishFailed(ids, e.getClass().getSimpleName() + ": " + e.getMessage(), now);
                publishFailures.increment();
                log.warn("outbox publish failed for {} rows, will retry with backoff: {}", ids.size(), e.toString());
                return rows.size();
            }
            // Broker has the messages. A crash right HERE is the ambiguous case: rows get republished.
            faults.crashIfArmed(FaultPoint.OUTBOX_CRASH_AFTER_CONFIRM);
            store.markPublished(ids, now);
            published.increment(ids.size());
            log.debug("outbox published {} rows", ids.size());
            return rows.size();
        });
        return claimed == null ? 0 : claimed;
    }

    private static String messageId(OutboxStore.PendingRow row) {
        return "outbox-" + row.id();
    }

    private static Message toMessage(OutboxStore.PendingRow row) {
        return MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setMessageId(messageId(row))
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)   // written to disk by the broker
                .setHeader("x-webhook-event-id", row.webhookEventId())
                .setHeader("x-outbox-id", row.id())
                .build();
    }
}
