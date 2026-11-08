# PROJECT_PROGRESS

_Last updated: 2026-11-08_

## Current phase
**Phase 1 — Core MVP: code complete, validation partial.** Phase 1 is *not* signed off until the JUnit suite
has run green (CI or locally). Do not start Phase 2 before that.

## Completed features
- Project setup: Maven, Spring Boot 3.5.16, Java 21, `application.yml` with env-only secrets, `.env.example`, `.gitignore`
- PostgreSQL schema V1–V4 (gateway tables, orders, payments, webhook_events, webhook_rejections, idempotency_records)
- Gateway simulator (Module A): state machine, `FOR UPDATE` locking, idempotent create/refund, versioned events,
  signed webhook delivery after commit, delivery log, manual redelivery
- Webhook ingestion (Module B): `POST /api/webhooks/{provider}`, provider adapter interface, raw-byte HMAC,
  constant-time compare, timestamp tolerance, secret rotation, schema validation, rejection log, durable dedup,
  operator read endpoints
- API idempotency (Module C part 1): `POST /api/checkout` with fingerprint, lease, fencing, recovery points,
  deterministic gateway key
- Error handling: structured `ApiError`; fixed catch-all that turned framework 404/405/400 into 500
- Infra: Dockerfile, docker-compose (PostgreSQL + backend), GitHub Actions CI (JUnit + SQL checks + pgbench)
- Docs: system design, database design (incl. designs for Phase 2–3 tables), ADRs, API, failure scenarios,
  interview guide (Phase 1 features), learning roadmap, testing results

## Actual tests run (2026-11-08)
| Test | Result |
|------|--------|
| Flyway migrations on PostgreSQL 16.15 | ✅ pass |
| `schema_checks.sql` | ✅ 21/21 pass |
| pgbench: 1,000 concurrent duplicate inserts (100 clients) | ✅ 1 row, 999 duplicates counted, 0 failures |
| pgbench: same with 200 clients | ✅ 1 row, 999 duplicates counted, 0 failures |
| Java-embedded SQL prepared against real schema | ✅ all valid |
| Pure-Java production classes compiled + 78-check smoke harness | ✅ 78/78 |
| **JUnit unit + integration suite (8 classes)** | ⏳ **written, NOT executed** — see limitation 1 |

## Known limitations
1. The development workspace blocks Maven Central and has no Docker, so the Spring application has **never been
   compiled or started**, and the JUnit suite has not run. First CI run may surface compile errors to fix.
2. Merchant `payments.status` is not yet updated from webhooks — events are stored as `RECEIVED` and wait for the
   Phase 2 worker. (This is exactly the "gateway CAPTURED, merchant still PENDING" gap the project exists to close.)
3. Gateway sends each webhook once automatically; if the app dies between commit and send, that event is never
   auto-delivered (deliberate: Phase 3 reconciliation must catch it). No automatic gateway retry schedule yet.
4. Checkout is behind ADMIN basic auth (demo simplification). No rate limiting yet (Phase 5).
5. Plain-text logs; JSON logs, correlation ids and metrics come in Phase 4.

## Pending features
Phase 2 outbox, RabbitMQ, worker, idempotent fulfilment, retries/DLQ · Phase 3 stale-event handling,
reconciliation, settlement, audit log · Phase 4 fault injection, metrics, React dashboard, demo mode, load tests ·
Phase 5 security review, rate limiting, final docs, demo video script.

## Next implementation steps
1. Push to GitHub → let CI run `mvn verify`; fix anything red; record real numbers in `docs/testing-results.md`.
2. Run the app (`docker compose up`) and walk the curl demo in `docs/api-documentation.md`.
3. Phase 2 kickoff: migration V5 (`outbox_events`, `fulfilments`, `processing_attempts`), write the outbox row in
   the webhook transaction, then the publisher.
