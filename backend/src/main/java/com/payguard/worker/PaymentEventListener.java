package com.payguard.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payguard.faults.FaultInjector;
import com.payguard.faults.FaultPoint;
import com.payguard.faults.SimulatedCrashException;
import com.payguard.messaging.RabbitTopology;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * RabbitMQ consumer with MANUAL acknowledgement.
 *
 * <p>The ordering rule that makes this reliable: <b>commit first, ack second.</b>
 * <ul>
 *   <li>crash before commit  -> nothing committed, no ack -> broker redelivers -> processed normally</li>
 *   <li>crash after commit, before ack -> broker redelivers -> event already PROCESSED -> skipped</li>
 * </ul>
 * The opposite order (ack, then commit) loses the event when the crash happens in between.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final EventProcessor processor;
    private final FailureHandler failureHandler;
    private final FaultInjector faults;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String workerId;

    public PaymentEventListener(EventProcessor processor, FailureHandler failureHandler, FaultInjector faults,
                                ObjectMapper objectMapper, Clock clock, MeterRegistry meters) {
        this.processor = processor;
        this.failureHandler = failureHandler;
        this.faults = faults;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meters = meters;
        // pid@host plus a random suffix: unique per process, readable in processing_attempts.
        this.workerId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    public String workerId() {
        return workerId;
    }

    @RabbitListener(id = "payment-event-worker", queues = RabbitTopology.QUEUE, ackMode = "MANUAL",
            autoStartup = "${payguard.worker.enabled:true}", concurrency = "${payguard.worker.concurrency:2-4}")
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        boolean redelivered = Boolean.TRUE.equals(message.getMessageProperties().getRedelivered());
        JsonNode payload = parse(message.getBody());
        Long webhookEventId = payload == null ? null : eventId(payload);
        if (webhookEventId == null) {
            // Poison message: retrying cannot help. requeue=false -> broker routes it to the DLQ.
            log.error("unparseable message rejected to DLQ messageId={}", message.getMessageProperties().getMessageId());
            channel.basicReject(tag, false);
            return;
        }

        Instant started = clock.instant();
        MDC.put("webhookEventId", webhookEventId.toString());
        putMdc("correlationId", payload.path("correlationId"));
        putMdc("providerEventId", payload.path("providerEventId"));
        putMdc("gatewayPaymentId", payload.path("gatewayPaymentId"));
        Timer.Sample sample = Timer.start(meters);
        String outcome = "unknown";
        try {
            EventProcessor.InjectedFaults injected = faults.isEnabled()
                    ? new EventProcessor.InjectedFaults(faults.shouldFire(FaultPoint.WORKER_TRANSIENT_ERROR),
                                                        faults.shouldFire(FaultPoint.WORKER_CRASH_BEFORE_COMMIT))
                    : EventProcessor.InjectedFaults.NONE;
            EventProcessor.Result result = processor.process(webhookEventId, workerId, redelivered, started, injected); // COMMITTED here
            outcome = result.name();
            faults.crashIfArmed(FaultPoint.WORKER_CRASH_AFTER_COMMIT);
            channel.basicAck(tag, false);
        } catch (SimulatedCrashException crash) {
            // What a crash looks like to the broker: the message was never acked, so it comes back.
            outcome = "CRASH";
            log.warn("simulated crash at {} for webhookEventId={} - message returned to the queue", crash.point(), webhookEventId);
            channel.basicNack(tag, false, true);
        } catch (Exception processingError) {
            outcome = "FAILED";
            handleFailure(message, channel, tag, webhookEventId, processingError, redelivered, started);
        } finally {
            sample.stop(Timer.builder("payguard.worker.processing").tag("outcome", outcome)
                    .description("time to process one payment event message").register(meters));
            MDC.remove("webhookEventId");
            MDC.remove("correlationId");
            MDC.remove("providerEventId");
            MDC.remove("gatewayPaymentId");
        }
    }

    private void handleFailure(Message message, Channel channel, long tag, long webhookEventId, Exception error,
                               boolean redelivered, Instant started) throws IOException {
        FailureHandler.Decision decision;
        try {
            decision = failureHandler.handle(webhookEventId, error, workerId, redelivered, started);
        } catch (Exception cannotRecord) {
            // We could not even record the failure (database down?). Do NOT ack - the message is our only
            // copy of the "work to do" signal. Pause briefly so we don't spin, then hand it back to the broker.
            log.error("could not record failure for webhookEventId={} ({}); returning message to queue",
                    webhookEventId, cannotRecord.toString());
            sleepQuietly(Duration.ofSeconds(1));
            channel.basicNack(tag, false, true);
            return;
        }
        meters.counter("payguard.worker.failures", "decision", decision.name()).increment();
        if (decision == FailureHandler.Decision.DEAD_LETTERED) {
            channel.basicReject(tag, false); // -> payguard.dlx -> payguard.payment-events.dlq (with x-death headers)
        } else {
            channel.basicAck(tag, false);    // retry (if any) lives in the outbox now
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static Long eventId(JsonNode node) {
        JsonNode id = node.get("webhookEventId");
        return id != null && id.isIntegralNumber() && id.canConvertToLong() ? id.asLong() : null;
    }

    private static void putMdc(String key, JsonNode value) {
        if (value != null && value.isTextual()) {
            MDC.put(key, value.asText());
        }
    }

    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
