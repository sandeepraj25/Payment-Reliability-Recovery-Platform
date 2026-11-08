# Architecture Decision Records

Short format: context → decision → consequences. Newest last.

## ADR-001 — Java 21 + Spring Boot 3.5.x
* **Context**: the brief requires Spring Boot 3.x on Java 21. Spring Boot 4.0 exists, but 3.5 is the version
  most interview material and company codebases use today.
* **Decision**: Spring Boot 3.5.16 (announced 2026-06-25 on spring.io). Use Java 21 features where they make code
  clearer: records for DTOs/rows, sealed interfaces + pattern-matching `switch` for idempotency outcomes.
* **Consequences**: patch upgrades within 3.5 are cheap; a 4.x move is a separate, deliberate step.

## ADR-002 — Modular monolith, gateway simulator in the same app but behind HTTP
* **Context**: we need realistic distributed-systems failures without microservice ops overhead.
* **Decision**: one Spring Boot app. The simulator has its own DB schema (`gateway`) and the merchant talks to it
  **only over HTTP** (and receives HTTP webhooks). Phase 2 adds RabbitMQ between ingestion and the worker.
* **Consequences**: timeouts, retries and duplicates are real; deploys stay one container. The simulator is
  disabled by default and must be switched on explicitly for local/demo use.

## ADR-003 — PostgreSQL is the source of truth for dedup and idempotency (not Redis)
* **Context**: "exactly once" effects need a guarantee that survives restarts and concurrency.
* **Decision**: unique constraints + `INSERT … ON CONFLICT` + row locks in PostgreSQL. Redis may be added later
  only for rate limiting.
* **Consequences**: one extra indexed write per request; in return the guarantee is transactional with the
  business data (both commit or neither does). Redis `SETNX` would be a second system that can disagree with the DB.

## ADR-004 — Plain SQL (`JdbcClient`) where concurrency semantics matter, JPA elsewhere
* **Decision**: JPA for simple aggregates (orders, payments, gateway payments). `JdbcClient` with explicit SQL for
  webhook dedup, idempotency leases and (Phase 2) outbox claiming.
* **Why**: `ON CONFLICT`, `FOR UPDATE`, `SKIP LOCKED`, fencing `WHERE lease_token = ?` must be visible and reviewable.
  JPA's `find → save` hides a race window.

## ADR-005 — Verify raw bytes; signature header `t=<unix>,v1=<hex>`
* **Decision**: the controller receives `byte[]`. HMAC-SHA256 over `"<t>." + body`, constant-time compare
  (`MessageDigest.isEqual`), ±5 min tolerance, several secrets for rotation (Stripe-style).
* **Consequences**: any JSON re-serialisation before verification would break signatures — tested explicitly.

## ADR-006 — Rejected webhooks go to a separate table
* **Decision**: `webhook_rejections`, never `webhook_events`.
* **Why**: otherwise a forged request could occupy a real event id in the dedup key space (squatting attack).

## ADR-007 — Idempotency leases with fencing tokens and recovery points
* **Context**: a request can crash halfway (after the order insert, after the gateway call).
* **Decision**: `IN_PROGRESS` + `locked_until` lease; a takeover rotates `lease_token`; all writes check it;
  `recovery_point` records progress so a retry resumes. The gateway call uses a **deterministic** key
  (`pgk_<paymentId>`), so a resumed request can never create a second gateway payment.
* **Alternatives**: one big transaction around the HTTP call (holds a DB connection and locks while waiting on
  the network; still cannot roll back the gateway) — rejected.

## ADR-008 — Tests: real PostgreSQL only; Testcontainers by default, external DB when Docker is absent
* **Decision**: `IntegrationTestBase` starts PostgreSQL 16 via Testcontainers, or uses `PAYGUARD_TEST_DB_URL`.
  No H2: it does not reproduce PostgreSQL's locking/`ON CONFLICT` behaviour, so green H2 tests would prove nothing.
* **Note**: the first development environment had no Docker and no Maven Central access; see `testing-results.md`.
