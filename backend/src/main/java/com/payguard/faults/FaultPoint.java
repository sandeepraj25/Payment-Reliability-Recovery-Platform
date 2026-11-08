package com.payguard.faults;

/**
 * Places in REAL production code paths where a fault can be injected (demo/test only).
 * Nothing here simulates the outcome; each point makes the real code fail at that spot,
 * and the real recovery mechanism has to fix it.
 */
public enum FaultPoint {
    /** Worker throws after doing all DB work but before COMMIT -> transaction rolls back, message is redelivered. */
    WORKER_CRASH_BEFORE_COMMIT,
    /** Worker committed but "dies" before acking -> broker redelivers an already-processed message. */
    WORKER_CRASH_AFTER_COMMIT,
    /** Worker hits a transient error (like a DB timeout) -> retry with backoff via the outbox. */
    WORKER_TRANSIENT_ERROR,
    /** Publisher got broker confirms but crashes before marking rows PUBLISHED -> rows republished (duplicates). */
    OUTBOX_CRASH_AFTER_CONFIRM,
    /** Publisher cannot reach the broker -> rows stay PENDING with backoff. */
    OUTBOX_BROKER_UNAVAILABLE,
    /** Gateway commits a state change but never sends the webhook -> only reconciliation can find it. */
    GATEWAY_DROP_WEBHOOK,
    /** Gateway API call from the merchant times out (ambiguous outcome). */
    GATEWAY_API_TIMEOUT,
    /** Webhook ingestion fails before commit, as if the database were down -> 503, gateway must retry. */
    INGESTION_DB_UNAVAILABLE
}
