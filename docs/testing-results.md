# Testing Results

Rule for this file: **only results that were actually executed are recorded, with where they ran.**
Numbers are copied from CI logs; nothing is estimated.

## 1. CI run 42448183066 — `main`, commit `1ab27c3` (2026-11-08)
GitHub-hosted `ubuntu-latest` runners. All 4 jobs green.

### Backend — `mvn -B verify`
**Unit tests (surefire): 155 run, 0 failures, 0 errors, 0 skipped**

| Class | Tests |
|-------|------:|
| `PaymentStateMachineTest` | 105 |
| `ReconciliationRulesTest` | 12 |
| `WebhookSignatureTest` | 12 |
| `WebhookPayloadParserTest` | 8 |
| `FailureClassifierTest` | 6 |
| `RetryPolicyTest` | 5 |
| `RequestFingerprintTest` | 4 |
| `RateLimitTest` | 3 |

**Integration tests (failsafe, real PostgreSQL 16 + RabbitMQ via Testcontainers): 80 run, 0 failures, 0 errors**

| Class | Tests | Time |
|-------|------:|-----:|
| `SimulationIT` (all 12 failure scenarios must end PASSED) | 13 | 31.1 s |
| `WebhookIngestionIT` | 12 | 12.9 s |
| `GatewaySimulatorIT` | 10 | 14.5 s |
| `CheckoutIT` | 10 | 12.1 s |
| `WorkerIT` | 9 | 17.7 s |
| `ReconciliationIT` | 8 | 22.8 s |
| `FailureRecoveryIT` | 7 | 14.4 s |
| `OpsIT` | 4 | 5.4 s |
| `OutboxIT` | 3 | 32.3 s |
| `WebhookConcurrencyIT` (100 and 1,000 simultaneous duplicate deliveries) | 2 | 4.0 s |
| `OutageIT` (real `docker pause` of PostgreSQL and RabbitMQ) | 2 | 17.8 s |

`BUILD SUCCESS`. The `ERROR` lines in that log are expected: tests deliberately cause dead-lettering
(`AMOUNT_MISMATCH`, `UNEXPECTED_TRANSITION`, orphan events) and DB outages, and assert the system's reaction.

### Schema job (PostgreSQL 16 service)
* Migrations V1–V8 applied; `schema_checks.sql`: `ALL SCHEMA CHECKS PASSED`.
* pgbench, 100 clients × 10 transactions of the same webhook insert: `rows=1 duplicate_count=999`.

### Frontend job
`npm ci && npm run build` (type-check + Vite production build) succeeded.

### e2e job
Real backend jar as an **API process plus a separate headless worker process**, PostgreSQL, RabbitMQ and the built
dashboard: **Playwright 6/6 passed** (11.2 s).

### Earlier red runs (kept for honesty)
Four of the nine `main` runs failed before being fixed. Each fix is a separate commit, and the bugs are explained in
`interview-guide.md` → "Bugs CI caught": a column too narrow for `SKIPPED_ALREADY_PROCESSED`, a wrong
exception-hierarchy assumption in failure classification, gateway timeouts surfacing as a generic exception, and
a worker-only process that could not start.

## 2. CI run 42466527296 — branch `phase-5-final-quality`, commit `43acfdb` (2026-11-08)
All 5 jobs green: backend **155 unit + 80 integration, 0 failures**; schema checks; frontend build;
Playwright **6/6**; and the new load job.

### Load test (`loadtest/run.mjs`, job `load`)
Environment: GitHub-hosted runner, 4 vCPU Intel Xeon 6975P-C, 15 GB. **The load generator, API process, worker
process, PostgreSQL and RabbitMQ all share this one VM**, so these numbers describe this setup only and are not
capacity claims. Rate limiter disabled for the run (from one IP it would cap throughput at 50 req/s). A warm-up run
(50 checkouts, 20 pipelines, 200 webhooks) preceded the measured run and is not reported.

**Correctness under load — all 12 invariants PASSED**
* 400/400 unique-key checkouts created; retry storm (50 keys × 6 simultaneous copies) produced **exactly 50 orders**
  (450 total = 400 + 50), only 201/409 responses, no key mapped to two orders.
* 200 captured orders all reached `FULFILLED` (0 timeouts); **200 fulfilments for 200 orders despite 150 duplicate
  redeliveries** of the capture webhook, all acknowledged 200.
* 2,000 signed webhooks with 20 % repeated ids: **1,600 stored once, 400 recognised as `DUPLICATE`**.
* No 5xx or transport errors anywhere, nothing dead-lettered, outbox fully drained.

**Measured latency and throughput**

| Workload | Concurrency | Requests | Throughput (req/s) | p50 ms | p95 ms | p99 ms | max ms | Status codes |
|----------|------------:|---------:|-------------------:|-------:|-------:|-------:|-------:|--------------|
| Webhook ingest (HMAC + durable insert + commit) | 40 | 2,000 | 1,289.6 | 28.6 | 51.1 | 60.9 | 70.4 | 200 × 2,000 |
| Checkout, unique keys (incl. HTTP call to gateway) | 20 | 400 | 24.5 | 804.5 | 1,153.4 | 1,264.2 | 1,387.8 | 201 × 400 |
| Checkout retry storm (same key × 6, all at once) | 300 | 300 | 45.8 | 5,004.4 | 6,499.1 | 6,531.7 | 6,537.7 | 201 × 50, 409 × 250 |
| Gateway capture | 20 | 200 | 10.6 | 385.8 | 601.9 | 680.2 | 726.5 | 200 × 200 |
| Capture → order FULFILLED, end to end ¹ | 20 | 200 | 10.6 | 628.9 | 1,071.9 | 1,129.6 | 1,188.3 | — |
| Duplicate webhook redelivery | — | 150 | 8.0 | 381.4 | 608.5 | 743.8 | 770.1 | 200 × 150 |

¹ webhook → ingest TX (event + outbox) → outbox poll (every 500 ms) → RabbitMQ → worker in a separate process →
payment update + fulfilment. Includes the poll wait; measured by polling the order every 100 ms (±100 ms).

**Reading the numbers (analysis, not measured fact)**
* Webhook ingest, which sends no login credentials, is ~28× faster at p50 than checkout. Every authenticated call pays
  a **BCrypt** password check (Spring's default encoder, cost 10), and a checkout pays two: our API's Basic auth, and the
  merchant → gateway call's Basic auth. One BCrypt verification measured **77.5 ms** single-core in the development
  workspace (a different, slower CPU than the runner). With 20 concurrent clients on 4 shared vCPUs, ~155 ms of CPU per
  checkout queues to roughly 20 × 155 / 4 ≈ 775 ms, close to the measured 805 ms p50. The retry storm fits the same model:
  300 simultaneous BCrypt checks ≈ 300 × 78 / 4 ≈ 5.9 s of queued CPU, versus measured p50 5.0 s / p95 6.5 s.
  This is consistent with BCrypt dominating, but it was **not isolated by experiment** on the runner.
* Implication: per-request password hashing is the wrong auth model for a hot API path. Production would authenticate
  once and use short-lived tokens (OIDC/JWT) or API keys checked with a fast HMAC lookup. It is acceptable for this
  local ops tool and is listed in Known limitations.
* The end-to-end p50 of ~630 ms is mostly the 500 ms outbox poll interval (average wait ~250 ms) plus processing; lowering
  the interval trades latency for more DB polling (or add `LISTEN/NOTIFY` to wake the publisher).

## 3. Development workspace checks (Phase 1, 2026-11-08)
Before CI existed, in a workspace without Maven Central access: migrations V1–V4 applied to PostgreSQL 16.15;
`schema_checks.sql` 21/21; pgbench 1,000 duplicate inserts at 100 and at 200 concurrent clients, each giving
1 row and `duplicate_count = 999` with 0 failed transactions; Java-embedded SQL validated with `PREPARE`; a 78-check
plain-Java smoke run of the pure production classes. These are superseded by the CI results above.

## How to reproduce
```bash
cd backend && mvn verify                                   # needs Docker for Testcontainers
psql -f backend/src/test/sql/schema_checks.sql             # against a migrated database
cd frontend && npx playwright test                         # against a running stack
BASE_URL=http://localhost:8080 ADMIN_PASSWORD=... WEBHOOK_SECRET=... node loadtest/run.mjs   # rate limiter off
```
