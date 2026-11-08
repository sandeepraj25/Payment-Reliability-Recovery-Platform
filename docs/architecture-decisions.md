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

## ADR-009 — Transactional outbox polled with `FOR UPDATE SKIP LOCKED` (not CDC)
* **Decision**: outbox row in the same transaction as the event; a scheduled publisher claims due rows with
  `SKIP LOCKED`, publishes with **publisher confirms**, marks PUBLISHED. Ambiguous outcome → stays PENDING → republished.
* **Why not Debezium/CDC**: another always-on component (Kafka Connect) for a modular monolith; polling at 500 ms is enough.
* **Consequence**: at-least-once publishing → every consumer must be idempotent (it is).

## ADR-010 — Retries scheduled through the outbox, not RabbitMQ TTL/delay queues
* **Decision**: a transient failure inserts a RETRY outbox row with `next_attempt_at = now + backoff(jitter)`, then acks.
* **Why**: per-message TTL queues have head-of-line blocking (a 30 s message blocks a 1 s one behind it); the delayed-message
  plugin is non-standard. The outbox already guarantees durability and is queryable.
* **Consequence**: retry precision ≈ poll interval (500 ms). Fine for payment events.

## ADR-011 — Manual ack after commit; dead letters via `basicReject(requeue=false)`
* **Decision**: commit, then ack. Permanent failures / exhausted budget → status DEAD in PostgreSQL (source of truth) +
  reject → broker dead-letters the original message (with `x-death` headers) to a 7-day DLQ parking lot.
* **Consequence**: duplicates after a crash are possible and handled; loss is not possible.

## ADR-012 — Versions + reachability for out-of-order events
* **Decision**: per-payment version from the gateway (bumped under its row lock). Merchant applies only newer versions,
  and only if the target state is reachable in the state machine graph.
* **Why not timestamps**: clock skew. **Why not reject everything that is not a single legal step**: a lost
  intermediate webhook would block valid newer state forever.

## ADR-013 — One guarded applier for worker and reconciliation
* **Decision**: `GatewayStateApplier` is the only code that copies gateway state onto merchant rows.
* **Why**: two code paths that both "fix" payments will eventually disagree; one path means one set of invariants and
  one set of tests.

## ADR-014 — Fault injection stored in PostgreSQL, decided outside the main transaction
* **Decision**: `fault_injections` table, consumed with `REQUIRES_NEW`, and checked **before** the work transaction.
* **Why**: works across processes; consumption survives the crash it triggers; avoids holding two pooled connections
  per thread (pool starvation under load).
* **Safety**: beans exist only when `payguard.faults.enabled=true`; production returns 404 for the lab URLs.

## ADR-015 — In-memory rate limiting, Redis only if horizontally scaled
* **Decision**: token bucket per client IP in a `ConcurrentHashMap` with eviction and a size cap.
* **Trade-off**: with N instances the effective limit is N×; acceptable for the target deployment. Redis is the
  justified upgrade path — and only for this, never for financial state or idempotency (ADR-003).

## ADR-016 — Same jar, different roles (API vs worker process)
* **Decision**: feature flags (`PAYGUARD_WORKER_ENABLED`, `…_OUTBOX_PUBLISHER_ENABLED`, `SPRING_MAIN_WEB_APPLICATION_TYPE=none`).
* **Why**: scale workers independently without a second codebase; CI runs the split topology end to end.
