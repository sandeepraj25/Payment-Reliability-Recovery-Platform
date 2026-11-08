package com.payguard.gateway;

import com.payguard.common.error.ApiException;
import com.payguard.webhook.signature.WebhookSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * Sends signed webhooks from the simulated gateway to the merchant endpoint.
 *
 * <p>Phase 1 scope: one automatic attempt after commit, plus manual redelivery through
 * the API (which is also how "duplicate delivery" is demonstrated). Automatic gateway
 * retries with backoff come with the failure-injection work in Phase 4.
 *
 * <p>Known gap, on purpose: if the app crashes between commit and this send, the event
 * row exists but is never delivered automatically. That is exactly the "missing webhook"
 * that reconciliation (Phase 3) must detect - real gateways have the same failure mode.
 */
@Component
@ConditionalOnProperty(prefix = "payguard.simulator", name = "enabled", havingValue = "true")
public class GatewayWebhookSender {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebhookSender.class);
    private static final String SIGNATURE_HEADER = WebhookSignature.SIGNATURE_HEADER;
    private static final String EVENT_ID_HEADER = WebhookSignature.EVENT_ID_HEADER;

    private final GatewayEventStore events;
    private final Environment environment;
    private final Clock clock;
    private final RestClient restClient;
    private final byte[] secret;

    public GatewayWebhookSender(GatewayEventStore events, SimulatorProperties props, Environment environment,
                                Clock clock, RestClient.Builder builder) {
        if (props.webhookSecret() == null || props.webhookSecret().length() < 16) {
            throw new IllegalStateException("payguard.simulator.webhook-secret must be at least 16 characters");
        }
        this.events = events;
        this.environment = environment;
        this.clock = clock;
        this.secret = props.webhookSecret().getBytes(StandardCharsets.UTF_8);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(2));
        factory.setReadTimeout((int) TimeUnit.SECONDS.toMillis(5));
        this.restClient = builder.requestFactory(factory).build();
    }

    public record DeliveryResult(String eventId, Integer httpStatus, String error) {
    }

    @Async("webhookExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventCreated(GatewayEventCreated created) {
        deliver(created.eventId(), "AUTO");
    }

    public DeliveryResult deliver(String eventId, String triggerType) {
        GatewayEventStore.StoredEvent event = events.findEvent(eventId)
                .orElseThrow(() -> ApiException.notFound("Event", eventId));
        String url = environment.getRequiredProperty("payguard.simulator.webhook-url");

        // Sign exactly the bytes we send.
        byte[] body = event.payload().getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.sign(clock.instant().getEpochSecond(), body, secret);

        long start = System.nanoTime();
        Integer status = null;
        String error = null;
        try {
            status = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SIGNATURE_HEADER, signature)
                    .header(EVENT_ID_HEADER, eventId)
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RestClientException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        int durationMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        events.recordDelivery(eventId, triggerType, status, error, durationMs);

        // Never log the secret or the signature.
        log.info("gateway webhook delivery event={} type={} trigger={} status={} error={} durationMs={}",
                eventId, event.eventType(), triggerType, status, error, durationMs);
        return new DeliveryResult(eventId, status, error);
    }
}
