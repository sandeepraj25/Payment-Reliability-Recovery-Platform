# PayGuard — Payment Reliability, Recovery & Reconciliation Platform

A merchant backend that stays correct when payment webhooks are **duplicated, delayed, out of order, forged,
or lost**, and when servers crash mid-request. Built as a learning/portfolio project for backend fintech roles.
No real money, no card data — the payment gateway is simulated.

**Current status: Phase 1 (Core MVP).** See [`PROJECT_PROGRESS.md`](PROJECT_PROGRESS.md) for exactly what is
implemented and which tests have actually been executed.

## What works in Phase 1
* Gateway simulator: create / authorize / capture / fail / refund, explicit state machine, row locking,
  idempotent create & refund, versioned events, **signed webhooks** sent after commit, manual redelivery.
* Webhook ingestion `POST /api/webhooks/{provider}`: raw-byte HMAC-SHA256, constant-time compare, timestamp
  replay window, secret rotation, schema validation, durable insert **before** 200, DB-enforced dedup.
* Checkout API `POST /api/checkout` with persistent idempotency: fingerprints, leases, fencing tokens,
  recovery points and a deterministic downstream gateway key (crash-safe, no double charge).

## Run locally
Requirements: Java 21, Maven 3.9, Docker (or a local PostgreSQL 16).

```bash
cp .env.example .env          # then edit every value
docker compose up --build     # PostgreSQL + backend on http://localhost:8080
```
Without Docker:
```bash
createdb payguard                                   # PostgreSQL 16 running locally
set -a; source .env; set +a
cd backend && mvn spring-boot:run
```
Health: `curl localhost:8080/actuator/health`. API walkthrough with curl: [`docs/api-documentation.md`](docs/api-documentation.md).

## Tests
```bash
cd backend
mvn test      # unit tests
mvn verify    # unit + integration (Testcontainers needs Docker)
# no Docker? point the integration tests at your own PostgreSQL:
PAYGUARD_TEST_DB_URL=jdbc:postgresql://localhost:5432/payguard_test PAYGUARD_TEST_DB_USER=payguard PAYGUARD_TEST_DB_PASSWORD=... mvn verify
# SQL-level checks:
psql -d payguard_test -f src/test/sql/schema_checks.sql
pgbench -n -c 100 -j 4 -t 10 -f src/test/sql/concurrent_webhook_dedup.pgbench payguard_test
```
CI (`.github/workflows/ci.yml`) runs all of the above on every push.

## Docs
| Doc | What |
|-----|------|
| [system-design](docs/system-design.md) | architecture, flows, states, invariants, folder structure, phase plan |
| [database-design](docs/database-design.md) | every table: purpose, keys, indexes, transactions, retention |
| [architecture-decisions](docs/architecture-decisions.md) | ADRs |
| [interview-guide](docs/interview-guide.md) | per-feature: problem, design, alternatives, crash behaviour, Q&A |
| [failure-scenarios](docs/failure-scenarios.md) | the 12 scenarios and their protection status |
| [api-documentation](docs/api-documentation.md) | endpoints, error codes, curl demo |
| [testing-results](docs/testing-results.md) | **only executed results** |
| [learning-roadmap](docs/learning-roadmap.md) | prerequisites in plain language |

## Security notes
Secrets come only from environment variables (no defaults for passwords/HMAC secrets — the app refuses to
start). The gateway simulator is **off by default**. `.env` is git-ignored. Never commit real credentials.
