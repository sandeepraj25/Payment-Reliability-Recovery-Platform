package com.payguard.idempotency;

import com.payguard.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistent API idempotency (Module C, part 1 of 3).
 *
 * <p>Three different things are often all called "idempotency"; PayGuard keeps them apart:
 * <ol>
 *   <li><b>API request idempotency</b> (this class): a client retrying POST /api/checkout
 *       with the same Idempotency-Key gets the same order, not a second one.</li>
 *   <li><b>Webhook event deduplication</b> (WebhookEventStore): the gateway delivering
 *       evt_123 twice stores it once. Keyed by the PROVIDER's event id.</li>
 *   <li><b>Idempotent fulfilment</b> (Phase 2): two DIFFERENT events (e.g. a duplicate
 *       "captured" with a new event id, or reconciliation recovering the same payment)
 *       must still ship the order once. Keyed by the ORDER, not by any request or event.</li>
 * </ol>
 * Each layer catches duplicates the others cannot see.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final Clock clock;

    public IdempotencyService(IdempotencyStore store, IdempotencyProperties props, Clock clock) {
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    /** Result of trying to claim a key. Java 21 sealed types make callers handle every case. */
    public sealed interface Claim permits Acquired, Replay, InFlight, FingerprintMismatch {
    }

    /** We own the key. {@code resumed=true} means a previous attempt crashed at {@code recoveryPoint}. */
    public record Acquired(long recordId, UUID leaseToken, String recoveryPoint, String resourceId,
                           boolean resumed) implements Claim {
    }

    /** The request already completed: return the stored response unchanged. */
    public record Replay(int responseStatus, String responseBody) implements Claim {
    }

    /** Another request currently owns this key and its lease is still valid. */
    public record InFlight() implements Claim {
    }

    /** Same key, different request body: a client bug. Never silently reuse the old result. */
    public record FingerprintMismatch() implements Claim {
    }

    @Transactional
    public Claim claim(String scope, String key, String fingerprint) {
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        Instant lockedUntil = now.plus(props.lease());
        Instant expiresAt = now.plus(props.ttl());

        if (store.tryInsert(scope, key, fingerprint, token, lockedUntil, expiresAt).isPresent()) {
            return newClaim(scope, key, token);
        }

        IdempotencyStore.Row row = store.findForUpdate(scope, key)
                .orElseThrow(() -> new IllegalStateException("idempotency row vanished for " + scope + "/" + key));

        if (row.expiresAt().toInstant().isBefore(now)) {
            store.resetExpired(row.id(), fingerprint, token, lockedUntil, expiresAt, now);
            return new Acquired(row.id(), token, "STARTED", null, false);
        }
        if (!row.fingerprint().equals(fingerprint)) {
            return new FingerprintMismatch();
        }
        if ("COMPLETED".equals(row.status())) {
            return new Replay(row.responseStatus(), row.responseBody());
        }
        if (row.lockedUntil() != null && row.lockedUntil().toInstant().isAfter(now)) {
            return new InFlight();
        }
        // Previous owner crashed or gave up. Take over and resume where it stopped.
        store.takeOver(row.id(), token, lockedUntil, now);
        log.info("idempotency takeover scope={} recoveryPoint={}", scope, row.recoveryPoint());
        return new Acquired(row.id(), token, row.recoveryPoint(), row.resourceId(), true);
    }

    private Acquired newClaim(String scope, String key, UUID token) {
        IdempotencyStore.Row row = store.findForUpdate(scope, key).orElseThrow();
        return new Acquired(row.id(), token, "STARTED", null, false);
    }

    /**
     * Records progress. MANDATORY: must run inside the caller's business transaction,
     * so "order created" and "recovery point = ORDER_CREATED" commit together or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(Acquired claim, String recoveryPoint, String resourceId) {
        if (store.advance(claim.recordId(), claim.leaseToken(), recoveryPoint, resourceId, clock.instant()) != 1) {
            throw leaseLost();
        }
    }

    /** Stores the final response in the caller's business transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Acquired claim, int responseStatus, String responseBody) {
        if (store.complete(claim.recordId(), claim.leaseToken(), responseStatus, responseBody, clock.instant()) != 1) {
            throw leaseLost();
        }
    }

    @Transactional
    public void release(Acquired claim) {
        store.release(claim.recordId(), claim.leaseToken(), clock.instant());
    }

    @Scheduled(fixedDelayString = "${payguard.idempotency.cleanup-interval:PT10M}")
    @Transactional
    public void deleteExpired() {
        int deleted = store.deleteExpired(clock.instant(), 1000);
        if (deleted > 0) {
            log.info("deleted {} expired idempotency records", deleted);
        }
    }

    private static ApiException leaseLost() {
        // Our lease expired and another request took over. Roll back our work; the new owner continues.
        return ApiException.conflict("IDEMPOTENCY_LEASE_LOST",
                "This request took too long and was superseded by a retry with the same Idempotency-Key");
    }
}
