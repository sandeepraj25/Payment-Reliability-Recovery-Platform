package com.payguard.simulation;

/**
 * The 12 required failure scenarios. {@code explanation} is shown in the Simulation Lab and in
 * Interview Demo Mode; {@code mechanisms} names the backend parts the scenario exercises.
 */
public enum Scenario {
    DUPLICATE_WEBHOOK("Duplicate webhook delivery",
            "The gateway delivers the same 'captured' event 5 more times (retries after timeouts are normal).",
            "UNIQUE(provider, provider_event_id) + INSERT ... ON CONFLICT; duplicates get 200 DUPLICATE and no outbox row"),
    DELAYED_WEBHOOK("Delayed webhook delivery",
            "The 'captured' webhook is held back by the gateway for a few seconds.",
            "Merchant stays PAYMENT_PENDING meanwhile (correct, not broken); processed normally on arrival"),
    OUT_OF_ORDER_EVENTS("Out-of-order events",
            "'captured' (v2) reaches the merchant before 'authorized' (v1).",
            "gateway version per payment: v1 <= applied v2 -> SKIPPED_STALE; reachability lets CREATED jump to CAPTURED"),
    WORKER_CRASH_BEFORE_COMMIT("Worker crash before DB commit",
            "The worker does all its work, then dies just before COMMIT.",
            "Transaction rollback leaves no trace; message never acked -> RabbitMQ redelivers -> processed once"),
    WORKER_CRASH_AFTER_COMMIT("Worker crash after commit, before ack",
            "The worker commits, then dies before acknowledging the message.",
            "Redelivered message finds the event PROCESSED -> SKIPPED_ALREADY_PROCESSED; UNIQUE(order_id) fulfilment"),
    DATABASE_UNAVAILABLE("Database temporarily unavailable",
            "The database fails while a webhook is being stored.",
            "No COMMIT -> HTTP 503 -> gateway retries later; nothing is acknowledged that was not stored"),
    RABBITMQ_UNAVAILABLE("RabbitMQ temporarily unavailable",
            "The broker is unreachable for the first publish attempts.",
            "Transactional outbox keeps the row PENDING with backoff; webhook was still acknowledged (durable in PostgreSQL)"),
    OUTBOX_PUBLISHER_CRASH("Outbox publisher crash",
            "The publisher gets broker confirms, then crashes before marking rows PUBLISHED.",
            "Rows roll back to PENDING and are republished (at-least-once); the worker deduplicates"),
    MISSING_WEBHOOK("Missing webhook",
            "The gateway captures the money but never sends the webhook.",
            "Reconciliation compares with the gateway status API, finds GATEWAY_CAPTURED_MERCHANT_PENDING, recovers safely"),
    CONCURRENT_DUPLICATE_REQUESTS("Concurrent duplicate requests",
            "50 identical checkout requests with the same Idempotency-Key arrive at the same time.",
            "idempotency_records UNIQUE(scope, key) + lease; one order, one gateway payment"),
    GATEWAY_API_TIMEOUT("Gateway API timeout",
            "The gateway creates the payment but answers after the merchant's read timeout.",
            "Ambiguous outcome made safe: retry with the same Idempotency-Key reuses the deterministic gateway key"),
    INVALID_SIGNATURE("Invalid webhook signature",
            "An attacker posts a 'captured' webhook signed with the wrong secret.",
            "HMAC-SHA256 over raw bytes, constant-time compare -> 401, stored only in webhook_rejections");

    private final String title;
    private final String explanation;
    private final String mechanisms;

    Scenario(String title, String explanation, String mechanisms) {
        this.title = title;
        this.explanation = explanation;
        this.mechanisms = mechanisms;
    }

    public String title() {
        return title;
    }

    public String explanation() {
        return explanation;
    }

    public String mechanisms() {
        return mechanisms;
    }
}
