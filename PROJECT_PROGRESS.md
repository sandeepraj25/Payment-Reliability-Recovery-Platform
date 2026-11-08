# PROJECT_PROGRESS

_Last updated: 2026-11-08_

## Current phase
**All five phases implemented and CI-verified.** `main` green at `1ab27c3` (run 42448183066); the Phase 5
additions (load test, final docs) are green on branch `phase-5-final-quality` (run 42467595295, `57e688b`), awaiting merge.

## Completed features
| Phase | Delivered |
|-------|-----------|
| 1 Core MVP | Gateway simulator (state machine, row locks, idempotent create/refund, signed webhooks, redelivery); webhook ingestion (raw-byte HMAC, constant-time compare, replay window, rotation, schema validation, durable dedup, rejection log); API idempotency (fingerprint, lease, fencing, recovery points, deterministic gateway key) |
| 2 Reliable processing | Transactional outbox in the ingestion TX; publisher with `FOR UPDATE SKIP LOCKED` + publisher confirms; RabbitMQ worker, ack after commit; idempotent fulfilment (`UNIQUE(order_id)`); retries with exponential backoff + jitter; transient vs permanent classification; DLQ with audited manual replay |
| 3 Correctness | Version-based stale/out-of-order handling; reachability rules; reconciliation (merchant vs gateway vs settlement); safe auto-recovery allow-list, manual review otherwise; settlement modelled separately from capture; append-only audit log (DB trigger); CSV export |
| 4 Differentiators | Failure Simulation Lab (12 scenarios on the real backend, DB evidence, invariant checks); ops/dashboard API; Micrometer metrics; JSON logs + correlation ids; React/TS dashboard (9 pages, light/dark); interview demo mode; Playwright e2e against the real stack |
| 5 Final quality | Security review; roles (ADMIN/VIEWER/GATEWAY_CLIENT); rate limiting; CORS; split API/worker processes; Docker Compose (postgres, rabbitmq, API, 2 workers, dashboard); CI (5 jobs); load test; full documentation |

## Actual tests run
| Where | What | Result |
|-------|------|--------|
| CI run 42448183066 (`main`, `1ab27c3`) | `mvn verify` | **155 unit + 80 integration tests, 0 failures, 0 errors** |
| same | SQL schema checks + pgbench 1,000-way dedup | all checks passed; `rows=1 duplicate_count=999` |
| same | frontend build; Playwright e2e (API + separate worker + PostgreSQL + RabbitMQ) | build ok; **6/6 passed** |
| CI run 42467595295 (branch `phase-5-final-quality`, `57e688b`) | all 5 jobs incl. load test | 155 + 80 tests, 0 failures; Playwright 6/6; **load test 13/13 invariants** (300-request retry storm → exactly 50 orders; 200 fulfilments for 200 orders despite 150 duplicate redeliveries; 400 of 2,000 webhooks recognised as duplicates). One earlier load run was red because of a load-generator flaw, fixed and documented in `docs/testing-results.md` §2 |
| Dev workspace (Phase 1) | migrations, 21 schema checks, pgbench ×2, 78 smoke checks | passed (details in testing-results) |

## Known limitations
1. **Not production-certified.** Self-review only, no penetration test (`docs/security-review.md`).
2. Auth is HTTP Basic + BCrypt per request (stateless, simple); production would use OIDC and short-lived tokens.
   It costs ~78 ms of CPU per request, which dominates checkout latency and amplifies retry storms (load test §2).
3. Rate limiting is per instance, in memory (effective limit = N × configured with N instances).
4. No TLS in the local stack; one DB role for app and migrations; no dependency scanning in CI yet.
5. Load-test numbers come from a shared GitHub runner where everything runs on one VM. They describe that
   machine only and are not capacity claims.
6. The development workspace blocks Maven Central, so Java is compiled and tested in CI, not locally there.

## Pending features
None of the brief's required features are outstanding. Possible next steps, not required:
Dependabot/OWASP dependency check; OIDC login; a real sandbox provider adapter (e.g. Razorpay test mode)
behind the existing `WebhookVerifier` interface; Redis-backed global rate limits.

## Next steps for you
1. Review and merge `phase-5-final-quality` into `main`.
2. Run `docker compose up --build` and walk `docs/demo-walkthrough.md` once yourself.
3. Record the 5-minute demo video using the script in that file.
4. Work through `docs/interview-guide.md`; re-implement one mechanism per day without looking.
