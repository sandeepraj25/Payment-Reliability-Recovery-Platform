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

## 2. Phase 5 branch `phase-5-final-quality` — load test (`loadtest/run.mjs`, CI job `load`)

Setup for every run: GitHub-hosted 4-vCPU runner; **the load generator, API process, worker process, PostgreSQL and
RabbitMQ all share that one VM**, so numbers describe that machine only and are not capacity claims. Rate limiter
disabled (from one IP it would cap throughput at 50 req/s). An unmeasured warm-up precedes each measured run.
The job fails only when a correctness invariant breaks; no latency thresholds exist.

### Run history (all three runs kept)
| Run | Commit | Runner CPU | Result | Notes |
|-----|--------|-----------|--------|-------|
| 42466527296 | `43acfdb` | Intel Xeon 6975P-C | all 5 jobs green | Load generator v1. Flaw found later: repeated webhooks were rebuilt with a new `created_at`, so their bodies differed from the original (PayGuard logged a warning for each; dedup still worked). |
| 42467008972 | `d03e262` (docs only) | — | **load job red**, 4 other jobs green | Retry storm got 12 × `503` (documented "retry with the same key" signal). v1 counted any 5xx as failure and never retried, so the run failed although the business result was still correct (450 orders, 200 fulfilments). |
| **42467595295** | **`57e688b`** | AMD EPYC 9V74 | **all 5 jobs green, 13/13 invariants** | Load generator v2: identical bodies for duplicates; checkout client retries 409/503 with the same key and backoff (honouring `Retry-After`), as the API contract says. Stricter invariant: every request must *end* in 201, same orderId per key; any 500/502/transport error fails. |

Run 42467595295 also re-ran the full suite: **155 unit + 80 integration tests, 0 failures**; Playwright **6/6**.

### Correctness under load — run 42467595295, 13/13 invariants passed
* No unexpected server errors (500/502) and no transport errors in any workload. Checkout only ever answered 201 or a
  documented retry signal.
* 400/400 unique-key checkouts ended in 201. The retry storm (50 keys × 6 simultaneous copies) ended with **all 300
  requests at 201 and exactly 50 orders** (450 total = 400 + 50), one orderId per key.
* 200 captured orders all reached `FULFILLED`; **200 fulfilments for 200 orders despite 150 duplicate redeliveries**.
* 2,000 signed webhooks with 20 % repeated ids (identical bodies): **1,600 stored once, 400 recognised as `DUPLICATE`**;
  0 "different payload" warnings.
* Nothing dead-lettered; outbox fully drained.

### Measured latency and throughput
| Workload | Conc. | Requests | req/s | p50 ms | p95 ms | p99 ms | max ms | Responses |
|----------|------:|---------:|------:|-------:|-------:|-------:|-------:|-----------|
| **Run 42467595295 (AMD EPYC, generator v2)** | | | | | | | | |
| Webhook ingest (HMAC + durable insert + commit) | 40 | 2,000 | 558.0 | 31.8 | 288.9 | 512.7 | 578.7 | 200 × 2,000 |
| Checkout, unique keys, first attempt | 20 | 400 | 20.3 | 988.8 | 1,290.5 | 1,393.2 | 1,494.9 | 201 × 400 (no retries needed) |
| Retry storm, first attempt | 300 | 300 | — | 6,239.7 | 9,758.3 | 10,832.3 | 15,012.5 | 409 × 248, 503 × 52 (`GATEWAY_UNAVAILABLE`) |
| Retry storm, time until final 201 (client retries) | 300 | 300 | — | 30,964.5 | 33,007.8 | 34,123.3 | 34,786.8 | 201 × 300, after 881 × 409 and 146 × 503 on the way |
| Gateway capture | 20 | 200 | 9.1 | 474.6 | 688.9 | 784.6 | 888.4 | 200 × 200 |
| Capture → order FULFILLED, end to end ¹ | 20 | 200 | 9.1 | 618.9 | 1,141.0 | 1,284.8 | 1,439.0 | — |
| Duplicate webhook redelivery | — | 150 | 6.9 | 471.1 | 733.5 | 774.6 | 779.3 | 200 × 150 |
| **Run 42466527296 (Intel Xeon, generator v1)** | | | | | | | | |
| Webhook ingest ² | 40 | 2,000 | 1,289.6 | 28.6 | 51.1 | 60.9 | 70.4 | 200 × 2,000 |
| Checkout, unique keys | 20 | 400 | 24.5 | 804.5 | 1,153.4 | 1,264.2 | 1,387.8 | 201 × 400 |
| Retry storm (no client retries) | 300 | 300 | — | 5,004.4 | 6,499.1 | 6,531.7 | 6,537.7 | 201 × 50, 409 × 250 |
| Gateway capture | 20 | 200 | 10.6 | 385.8 | 601.9 | 680.2 | 726.5 | 200 × 200 |
| Capture → order FULFILLED, end to end ¹ | 20 | 200 | 10.6 | 628.9 | 1,071.9 | 1,129.6 | 1,188.3 | — |

¹ webhook → ingest TX (event + outbox) → outbox poll (every 500 ms) → RabbitMQ → worker in a separate process → payment
update + fulfilment. Includes the poll wait; measured by polling the order every 100 ms (±100 ms).
² v1 generator: repeated deliveries carried a different body (see run history); same code path, plus one warning log each.

### Reading the numbers (analysis, not measured fact)
* **Runner variance is large.** Same code, same workload: webhook ingest p95 was 51 ms on one runner and 289 ms on
  another. One run on a shared CI VM is evidence of behaviour, not a benchmark.
* **Authentication cost dominates authenticated calls.** Webhooks (no login, HMAC only) are ~25-30× faster at p50 than
  checkout. Every Basic-auth request re-verifies a BCrypt hash (Spring's default encoder, cost 10), and a checkout does it
  twice: our API's auth plus the merchant → gateway call. One BCrypt verification measured **77.5 ms** single-core in the
  development workspace (a different CPU). 20 concurrent checkouts × ~155 ms CPU / 4 vCPUs ≈ 775 ms of queueing, close to
  the measured 805-989 ms p50. Consistent with BCrypt dominating, but **not isolated by experiment** on the runners.
* **Retry amplification.** In the storm, 300 logical requests became ~1,327 HTTP attempts. Each attempt pays BCrypt, so
  retries add CPU to an already saturated machine and the storm took ~31 s (p50) to converge. Correctness held throughout;
  only latency suffered. Backoff + `Retry-After` kept it bounded. Without them the retries would arrive as a tight loop.
* **What would fix it in production:** authenticate once and use short-lived tokens or HMAC-checked API keys (not BCrypt per
  request); size the gateway read timeout and DB pool for expected concurrency; cap concurrent work per key.
* End-to-end p50 ≈ 620-630 ms is mostly the 500 ms outbox poll interval (average wait ~250 ms). `LISTEN/NOTIFY` could wake
  the publisher right after commit, with polling kept as the safety net.

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
