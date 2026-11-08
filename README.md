# PayGuard — Payment Reliability, Recovery & Reconciliation Platform

A merchant backend that stays correct when payment webhooks are **duplicated, delayed, out of order, forged
or lost**, and when servers, the database or the message broker fail mid-request. Built as a learning and
portfolio project for backend fintech roles. No real money, no card data: the payment gateway is simulated.

**Status: all five phases implemented; CI green.** What is verified, and how, is in
[`docs/testing-results.md`](docs/testing-results.md). This is a carefully tested design, **not** a
production-certified system (see [`docs/security-review.md`](docs/security-review.md) and
[`PROJECT_PROGRESS.md`](PROJECT_PROGRESS.md) → Known limitations).

## The problem
A customer pays ₹4,999. The gateway captures the money and sends a webhook. Our server crashes at that moment,
so our database still says `PENDING`: the customer paid and nothing ships. Or the webhook arrives twice and
the order ships twice. PayGuard prevents, detects, explains and recovers from these situations, and lets
you **inject each failure for real** and watch the recovery.

## Architecture
```
Gateway Simulator ──signed webhook──► Webhook API ──one TX──► PostgreSQL (event + outbox row)
                                                                  │
                         Outbox Publisher (FOR UPDATE SKIP LOCKED, publisher confirms)
                                                                  ▼
                                   RabbitMQ (durable queue, retry queues, DLQ)
                                                                  ▼
                 Worker process(es): state machine + version check → payment update → idempotent fulfilment
                                                                  ▼
                 Reconciliation: merchant DB vs gateway status API vs settlement reports → safe auto-fix or manual review
```
One Spring Boot jar, deployed in two roles: an **API** process and separate **worker** processes. Details:
[`docs/system-design.md`](docs/system-design.md).

## What it does
| Module | Highlights |
|--------|-----------|
| Gateway simulator | state machine, row locks, idempotent create/refund, versioned events, HMAC-signed webhooks, redelivery, settlement reports |
| Webhook ingestion | raw-byte HMAC-SHA256, constant-time compare, replay window, secret rotation, schema validation, event + outbox committed together **before** 200 |
| Idempotency | API keys with fingerprint, lease, fencing token and recovery points; event dedup by `UNIQUE(provider, event_id)`; fulfilment dedup by `UNIQUE(order_id)` |
| Outbox + worker | `SKIP LOCKED` claiming, publisher confirms, ack only after commit, stale-version detection, transient vs permanent failures |
| Retries + DLQ | exponential backoff with jitter, max attempts, dead-letter queue, audited manual replay |
| Reconciliation | merchant vs gateway vs settlement; auto-recovery only for provably safe cases, manual review otherwise; CSV export |
| Failure Simulation Lab | 12 scenarios injected into the real backend (crash before/after commit, DB/broker down, lost webhook, …) with DB evidence and invariant checks |
| Observability | JSON logs with correlation ids, Micrometer/Prometheus metrics, health endpoints |
| Dashboard | React + TypeScript: dashboard, payments, webhooks, failure lab, reconciliation, DLQ, health, architecture explorer, interview demo mode; light/dark |

## Run it
Requirements: Docker. (For development without Docker: Java 21, Maven 3.9, Node 22, PostgreSQL 16, RabbitMQ 3.13.)
```bash
cp .env.example .env            # set every password (>= 12 characters)
docker compose up --build       # postgres, rabbitmq, API, 2 workers, dashboard
```
* Dashboard: http://localhost:5173 (sign in with `PAYGUARD_ADMIN_USER` / `PAYGUARD_ADMIN_PASSWORD`)
* API health: `curl localhost:8080/actuator/health`
* RabbitMQ UI: http://localhost:15672

Guided tour and a 5-minute video script: [`docs/demo-walkthrough.md`](docs/demo-walkthrough.md).
API reference with curl examples: [`docs/api-documentation.md`](docs/api-documentation.md).

## Tests
```bash
cd backend && mvn verify            # 155 unit + 80 integration tests (Testcontainers: needs Docker)
cd frontend && npx playwright test  # e2e against a running stack
node loadtest/run.mjs               # load test (see header of the file for env vars)
```
Without Docker, point integration tests at your own PostgreSQL with `PAYGUARD_TEST_DB_URL`,
`PAYGUARD_TEST_DB_USER`, `PAYGUARD_TEST_DB_PASSWORD`.

CI (`.github/workflows/ci.yml`) runs on every push: backend unit + integration tests, SQL schema checks and a
1,000-way concurrent dedup test, frontend build, Playwright e2e against the real split API/worker stack, and the
load test.

## Docs
| Doc | What |
|-----|------|
| [system-design](docs/system-design.md) | architecture, flows, states, invariants, folder structure |
| [database-design](docs/database-design.md) | every table: purpose, keys, indexes, transactions, retention |
| [architecture-decisions](docs/architecture-decisions.md) | ADRs: what we chose, what we rejected, why |
| [interview-guide](docs/interview-guide.md) | per feature: problem, design, alternatives, crash behaviour, Q&A, bugs CI caught |
| [failure-scenarios](docs/failure-scenarios.md) | the 12 scenarios, how each is injected and what protects us |
| [api-documentation](docs/api-documentation.md) | endpoints, error codes, curl demo |
| [testing-results](docs/testing-results.md) | **only executed results**, with CI run ids |
| [security-review](docs/security-review.md) | what is protected, what is knowingly left out |
| [demo-walkthrough](docs/demo-walkthrough.md) | demo steps and video script |
| [learning-roadmap](docs/learning-roadmap.md) | prerequisites in plain language |

## Security notes
Secrets come only from environment variables; the app refuses to start without them. The gateway simulator
and the failure lab are **off by default** and do not exist (404) unless enabled. `.env` is git-ignored.
