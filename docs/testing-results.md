# Testing Results

Rule for this file: **only results that were actually executed are recorded as passed.** Everything else
is listed as *written, not yet executed*.

## Run log

### 2026-11-08 — development environment (cloud workspace)

Environment: PostgreSQL 16.15 (local), OpenJDK 21.0.12, 2 vCPU, 7 GB RAM.
Limitation: the workspace's network policy **blocks Maven Central** (HTTP 403 from the egress proxy) and Docker is
not installed, so Spring Boot dependencies could not be downloaded and **the JUnit suite could not be compiled or
run here**. It will run in GitHub Actions (`.github/workflows/ci.yml`) or locally with `mvn verify`.

| # | What | How | Result |
|---|------|-----|--------|
| 1 | Flyway migrations V1–V4 apply cleanly | `psql -v ON_ERROR_STOP=1 -f V*.sql` on an empty DB | ✅ executed, passed |
| 2 | Schema invariant checks (21 checks: money, FKs, uniqueness, dedup key, status lists, idempotency completeness, refund keys, event versions) | `psql -f src/test/sql/schema_checks.sql` | ✅ executed — **21/21 PASS** + 1 documented behaviour (BIGINT rounds 4999.50 → 5000) |
| 3 | DB-level concurrent dedup: 1,000 inserts of the same event, 100 concurrent connections | `pgbench -n -c 100 -j 2 -t 10 -f concurrent_webhook_dedup.pgbench` | ✅ executed — 1000/1000 transactions, **0 failed**, **1 row**, `duplicate_count = 999` |
| 4 | Same, 200 concurrent connections | `pgbench -n -c 200 -j 2 -t 5 …` | ✅ executed — 1000/1000, **0 failed**, **1 row**, `duplicate_count = 999` |
| 5 | SQL embedded in Java stores (webhook insert/duplicate/list/rejection, idempotency insert/lock/complete/cleanup) is valid against the real schema | `PREPARE`/`EXECUTE` in psql, rolled back | ✅ executed, all statements valid |
| 6 | Pure production classes compile (state machine, HMAC signature, fingerprint, payload parser) | `javac` with Jackson 2.16 + a compile-only `@Component` stand-in | ✅ executed, compiled |
| 7 | Plain-Java smoke harness over those production classes (49 state pairs + 1 count check, 14 signature cases, 2 fingerprint, 12 parser cases) | `java Smoke` | ✅ executed — **78 passed, 0 failed** (this is *not* the JUnit suite) |
| 8 | Syntax check of every Java file | `javac` without libraries: only "symbol/package not found" errors, no syntax errors | ✅ executed (partial check only) |

Raw output of #3:
```
number of clients: 100
number of transactions per client: 10
number of transactions actually processed: 1000/1000
number of failed transactions: 0 (0.000%)
 rows_for_event | duplicate_count
              1 |             999
```
(Throughput figures printed by pgbench in this 2-vCPU sandbox are not benchmarks and are intentionally not quoted.)

## JUnit suite — written, NOT yet executed

| Class | Type | Covers |
|-------|------|--------|
| `PaymentStateMachineTest` | unit | all 49 transitions vs hand-written table; stale-regression cases; terminal states |
| `WebhookSignatureTest` | unit | valid, tampered, whitespace change, wrong secret, rewritten timestamp, replay, future, malformed, rotation |
| `RequestFingerprintTest` | unit | stability, concatenation-collision guard |
| `WebhookPayloadParserTest` | unit | fractional/string/negative money, unknown types, structure, UTF-8 |
| `WebhookIngestionIT` | integration | stored-before-ack, duplicate, invalid/missing/stale signature, tampered body, squatting attack, malformed, unknown type, header mismatch, unknown provider, auth |
| `WebhookConcurrencyIT` | concurrency | **100 and 1,000** simultaneous duplicate deliveries over real HTTP |
| `GatewaySimulatorIT` | integration + concurrency | create idempotency, 422 key reuse, invalid transition, versioned events, 50 concurrent captures, refund idempotency, 10 concurrent over-refunds, signed webhook end-to-end, redelivery |
| `CheckoutIT` | integration + concurrency | create, identical replay, 422, 100 concurrent same-key requests, **crash-after-gateway-call resume**, 409 in-flight, validation, framework error codes |

To run: `cd backend && mvn verify` (Docker running) — or without Docker:
```
PAYGUARD_TEST_DB_URL=jdbc:postgresql://localhost:5432/payguard_test \
PAYGUARD_TEST_DB_USER=payguard PAYGUARD_TEST_DB_PASSWORD=... mvn verify
```
Paste the summary here (tests run / failures / errors) after the first real run.
