package com.payguard.webhook;

import com.payguard.common.crypto.Hashing;
import com.payguard.common.error.ApiException;
import com.payguard.faults.FaultInjector;
import com.payguard.faults.FaultPoint;
import com.payguard.outbox.OutboxStore;
import com.payguard.webhook.signature.WebhookSignature;
import com.payguard.webhook.verification.WebhookVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Module B: the webhook ingestion pipeline.
 *
 * <pre>
 *   raw bytes ──► size check ──► signature (provider adapter) ──► schema validation
 *                                    │ fail                          │ fail
 *                                    ▼                               ▼
 *                           webhook_rejections                webhook_rejections
 *                                                                    │ ok
 *                                                                    ▼
 *                         BEGIN; INSERT ... ON CONFLICT (dedup); COMMIT   ──► 200 OK
 * </pre>
 *
 * The 200 is returned only after COMMIT, so "acknowledged" always means "durably stored".
 * The outbox row is written in that SAME transaction (Module D).
 */
@Service
public class WebhookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestionService.class);
    private static final int REJECTION_EXCERPT_CHARS = 500;

    private final Map<String, WebhookVerifier> verifiers;
    private final WebhookPayloadParser parser;
    private final WebhookEventStore store;
    private final WebhookProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OutboxStore outbox;
    private final FaultInjector faults;

    public WebhookIngestionService(List<WebhookVerifier> verifiers, WebhookPayloadParser parser,
                                   WebhookEventStore store, WebhookProperties props,
                                   TransactionTemplate tx, Clock clock, OutboxStore outbox, FaultInjector faults) {
        this.outbox = outbox;
        this.faults = faults;
        this.verifiers = verifiers.stream().collect(Collectors.toUnmodifiableMap(WebhookVerifier::provider, Function.identity()));
        this.parser = parser;
        this.store = store;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    public enum Result { ACCEPTED, DUPLICATE, IGNORED }

    public record Accepted(Result result, String eventId, long webhookEventId) {
    }

    /** Thrown for requests we refuse. Carries the HTTP status so the controller stays thin. */
    public static class RejectedException extends ApiException {
        private final RejectionReason reason;

        RejectedException(HttpStatus status, RejectionReason reason, String message) {
            super(status, reason.name(), message);
            this.reason = reason;
        }

        public RejectionReason reason() {
            return reason;
        }
    }

    public Accepted ingest(String provider, HttpHeaders headers, byte[] rawBody, String sourceIp) {
        WebhookVerifier verifier = verifiers.get(provider);
        if (verifier == null) {
            // Not stored: the provider string is attacker-controlled and there is no secret to check against.
            throw ApiException.notFound("Webhook provider", provider);
        }
        if (rawBody == null || rawBody.length == 0) {
            throw reject(provider, headers, new byte[0], sourceIp, HttpStatus.BAD_REQUEST,
                    RejectionReason.MALFORMED_PAYLOAD, "empty body");
        }
        if (rawBody.length > props.maxBodyBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "Webhook body exceeds " + props.maxBodyBytes() + " bytes");
        }

        // 1) AUTHENTICITY before anything else. Nothing unverified is parsed into business objects.
        WebhookVerifier.Result verification = verifier.verify(headers, rawBody);
        if (!verification.valid()) {
            throw reject(provider, headers, rawBody, sourceIp, HttpStatus.UNAUTHORIZED,
                    verification.reason(), verification.detail());
        }

        // 2) SCHEMA. The sender is authentic, but the content must still make sense.
        WebhookPayloadParser.ParsedEvent event;
        try {
            event = parser.parse(rawBody);
        } catch (WebhookPayloadParser.MalformedPayloadException e) {
            throw reject(provider, headers, rawBody, sourceIp, HttpStatus.BAD_REQUEST,
                    RejectionReason.MALFORMED_PAYLOAD, e.getMessage());
        }
        String headerEventId = headers.getFirst(WebhookSignature.EVENT_ID_HEADER);
        if (headerEventId != null && !headerEventId.equals(event.eventId())) {
            throw reject(provider, headers, rawBody, sourceIp, HttpStatus.BAD_REQUEST,
                    RejectionReason.MALFORMED_PAYLOAD, "event id header does not match body id");
        }

        // 3) DURABLE STORE + DEDUP, in a transaction that has COMMITTED when execute() returns.
        String sha = Hashing.sha256Hex(rawBody);
        // Transactional outbox: the event row and its "please process me" row commit TOGETHER.
        // There is no moment where the event is stored but nobody will ever process it.
        // Fault check happens BEFORE the transaction: it uses its own connection, and taking a second
        // connection while holding one can starve the pool under load (all threads waiting for an 11th).
        if (faults.shouldFire(FaultPoint.INGESTION_DB_UNAVAILABLE)) {
            throw new org.springframework.dao.DataAccessResourceFailureException("simulated: database unavailable");
        }
        WebhookEventStore.InsertResult stored = tx.execute(status -> {
            Instant now = clock.instant();
            WebhookEventStore.InsertResult r = store.insertOrMarkDuplicate(provider, event, sha, now);
            if (!r.duplicate() && event.known()) {
                outbox.insert(r.id(), OutboxStore.Purpose.INGEST, event.eventId(), event.type(), event.paymentId(), now);
            }
            return r;
        });

        if (stored.duplicate()) {
            if (!sha.equals(stored.existingPayloadSha256())) {
                // Same event id, different signed content. The gateway re-signs on every delivery, but the
                // body should be identical. We keep the FIRST version and flag it for investigation.
                log.warn("webhook duplicate with different payload provider={} eventId={} webhookEventId={}",
                        provider, event.eventId(), stored.id());
            }
            log.info("webhook duplicate provider={} eventId={} webhookEventId={}", provider, event.eventId(), stored.id());
            return new Accepted(Result.DUPLICATE, event.eventId(), stored.id());
        }
        log.info("webhook stored provider={} eventId={} type={} paymentId={} version={} webhookEventId={}",
                provider, event.eventId(), event.type(), event.paymentId(), event.paymentVersion(), stored.id());
        return new Accepted(event.known() ? Result.ACCEPTED : Result.IGNORED, event.eventId(), stored.id());
    }

    private RejectedException reject(String provider, HttpHeaders headers, byte[] body, String sourceIp,
                                     HttpStatus status, RejectionReason reason, String detail) {
        String claimedId = headers.getFirst(WebhookSignature.EVENT_ID_HEADER);
        String excerpt = new String(body, 0, Math.min(body.length, REJECTION_EXCERPT_CHARS), StandardCharsets.UTF_8);
        store.insertRejection(provider, claimedId, reason, detail,
                body.length == 0 ? null : Hashing.sha256Hex(body), excerpt, sourceIp);
        // Never log the signature header or the body: only the reason.
        log.warn("webhook rejected provider={} reason={} detail={} claimedEventId={} ip={}",
                provider, reason, detail, claimedId, sourceIp);
        return new RejectedException(status, reason, "Webhook rejected: " + reason);
    }
}
