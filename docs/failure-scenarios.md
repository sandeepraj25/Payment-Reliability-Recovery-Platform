# Failure Scenarios

Each scenario: what goes wrong → what protects us → evidence. Status reflects the current code.
The interactive Failure Simulation Lab (real backend fault injection) comes in Phase 4.

| # | Scenario | Protection | Status | Evidence |
|---|----------|------------|--------|----------|
| 1 | Duplicate webhook delivery | `UNIQUE (provider, provider_event_id)` + `ON CONFLICT`; 200 `DUPLICATE` | ✅ Phase 1 | `WebhookIngestionIT`, `WebhookConcurrencyIT`, pgbench run (`testing-results.md` #3/#4) |
| 2 | Delayed webhook | Stored whenever it arrives; ordering handled by version (Phase 2/3) | ◐ storage only | — |
| 3 | Out-of-order events | `gateway_payment_version` stored now; worker rejects `version <= current` | Phase 2/3 | — |
| 4 | Worker crash before DB commit | Manual ack after commit ⇒ redelivery | Phase 2 | — |
| 5 | Worker crash after commit, before ack | Redelivery + idempotent fulfilment | Phase 2 | — |
| 6 | Database unavailable during webhook | No commit ⇒ no 200 ⇒ gateway retries | ✅ by design (I1) | test planned with fault injection (Phase 4) |
| 7 | RabbitMQ unavailable | Outbox keeps events; publisher retries | Phase 2 | — |
| 8 | Outbox publisher crash | Rows stay PENDING; `SKIP LOCKED` claim resumes | Phase 2 | — |
| 9 | Missing webhook | Gateway can lose it after commit (deliberate gap) → reconciliation detects | Phase 3 | — |
| 10 | Concurrent duplicate requests | Idempotency key claim + row lock | ✅ Phase 1 | `CheckoutIT.hundredConcurrentRetriesCreateOneOrder` |
| 11 | Gateway API timeout during checkout | Ambiguous outcome made safe by deterministic gateway key; 503 tells client to retry with same key | ✅ Phase 1 | `CheckoutIT.retryAfterCrashResumes…` (crash variant) |
| 12 | Invalid webhook signature | Rejected before parsing, stored only in `webhook_rejections` | ✅ Phase 1 | `WebhookIngestionIT` (invalid, tampered, missing, replay, squatting) |
| + | Crash mid-checkout | Recovery points + lease takeover with fencing | ✅ Phase 1 | `CheckoutIT` |
| + | Fractional money (`4999.50`) | Rejected at API/parser; DB alone would round (documented) | ✅ Phase 1 | `schema_checks.sql`, parser tests |

✅ = protection implemented. Whether the listed test has been *executed* is tracked only in `testing-results.md`.
