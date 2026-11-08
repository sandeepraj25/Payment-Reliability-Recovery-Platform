# Failure Scenarios

All 12 required scenarios are implemented in the **Failure Simulation Lab** (`SimulationRunner`), each running
the real backend with a real injected fault, and each also covered by a focused integration test.
`SimulationIT` runs all 12 through the API and requires every one to end `PASSED` (all invariants held).

| # | Scenario | Injected how | Protection | Focused test |
|---|----------|--------------|------------|--------------|
| 1 | Duplicate webhook delivery | gateway redelivers the same event 5× | `UNIQUE(provider, provider_event_id)` + `ON CONFLICT`; duplicates → 200 `DUPLICATE`, no outbox row | `WorkerIT.redeliveredWebhooksNeverShipTwice`, `WebhookConcurrencyIT` (100 / 1,000) |
| 2 | Delayed webhook | `GATEWAY_DELAY_WEBHOOK` | merchant waits in PAYMENT_PENDING (no guessing); processed on arrival | simulation `DELAYED_WEBHOOK` |
| 3 | Out-of-order events | v2 delivered before v1 | version check → v1 `SKIPPED_STALE`; reachability lets CREATED → CAPTURED | `WorkerIT.outOfOrderEventsNeverMovePaymentBackwards`, `shuffledConcurrentEvents…` |
| 4 | Worker crash before DB commit | `WORKER_CRASH_BEFORE_COMMIT` | rollback; unacked → redelivered | `FailureRecoveryIT.workerCrashBeforeCommit…` |
| 5 | Worker crash after commit, before ack | `WORKER_CRASH_AFTER_COMMIT` | redelivery → `SKIPPED_ALREADY_PROCESSED`; `UNIQUE(order_id)` | `FailureRecoveryIT.workerCrashAfterCommitBeforeAckIsHarmless` |
| 6 | Database unavailable | `INGESTION_DB_UNAVAILABLE`; **real**: `docker pause` PostgreSQL | no commit → 503 → gateway retries; never a false 2xx | `FailureRecoveryIT.webhookIsNotAcknowledgedWhenItCannotBeStored`, `OutageIT.databaseOutage…` |
| 7 | RabbitMQ unavailable | `OUTBOX_BROKER_UNAVAILABLE`; **real**: `docker pause` RabbitMQ | outbox keeps rows PENDING with backoff; webhooks still acknowledged | `FailureRecoveryIT.brokerUnavailable…`, `OutageIT.rabbitMqOutage…` |
| 8 | Outbox publisher crash | `OUTBOX_CRASH_AFTER_CONFIRM` | rows roll back to PENDING → republished → consumer dedups | `FailureRecoveryIT.outboxPublisherCrash…` |
| 9 | Missing webhook | `GATEWAY_DROP_WEBHOOK` | reconciliation → `GATEWAY_CAPTURED_MERCHANT_PENDING` → guarded auto-recovery | `ReconciliationIT.lostCaptureWebhookIsDetectedAndSafelyRecovered` |
| 10 | Concurrent duplicate requests | 50 / 100 identical checkouts | `idempotency_records` claim + lease; one order, one gateway payment | `CheckoutIT.hundredConcurrentRetriesCreateOneOrder` |
| 11 | Gateway API timeout | `GATEWAY_API_TIMEOUT` (gateway commits, answers late) | 503 to client; retry with same key reuses deterministic gateway key | `CheckoutIT.retryAfterCrashResumes…`, simulation `GATEWAY_API_TIMEOUT` |
| 12 | Invalid webhook signature | wrong-secret HMAC | 401, stored only in `webhook_rejections`, state untouched | `WebhookIngestionIT` (invalid, tampered, missing, replay, squatting) |

Extra scenarios also tested: crash mid-checkout (recovery points), amount mismatch (dead-lettered, flagged), impossible
transition (dead-lettered), double charge (flagged for refund), missing / delayed settlement.

## Every simulation records

1. **Initial state** — order, merchant payment, gateway payment, events, attempts, fulfilments (from SQL)
2. **Injected failure** — which fault point was armed
3. **Observed state** — the broken / incomplete state, with evidence
4. **Recovery mechanism** — what fixed it, with evidence
5. **Final verified state** + **invariants** checked against the database (e.g. "exactly one fulfilment",
   "merchant matches gateway")

Runs are stored in `simulation_runs` (JSONB timeline) and shown in the dashboard and Interview Demo Mode.
