# PayGuard — System Design

> Status: Phases 1–5 implemented; all automated tests green in CI (see `testing-results.md`).

## 1. The problem in one paragraph

A customer pays ₹4,999. The gateway (Razorpay/Stripe/…) captures the money and calls our server
with a **webhook**: "payment `pay_123` is CAPTURED". At that exact moment our server crashes, or the
database is down, or the message is delivered twice, or a stale "authorized" event arrives *after*
"captured". Result: the customer paid, but our database still says `PENDING` and the order is never
shipped — or it ships twice. PayGuard is a merchant backend built to **prevent, detect, explain and
recover** from exactly these situations.

**Real-life analogy.** Think of the gateway as a bank that posts you letters ("your customer paid").
The post office sometimes delivers a letter twice, sometimes late, sometimes out of order, and
sometimes you are not home. PayGuard is the careful office clerk who
1. checks the seal on every letter (HMAC signature),
2. files it in a fire-proof cabinet *before* signing the courier's receipt (durable storage before 200 OK),
3. never files the same letter twice (deduplication),
4. never ships an order twice even if two *different* letters say "paid" (idempotent fulfilment — Phase 2),
5. calls the bank every night to compare notes (reconciliation — Phase 3).

## 2. Architecture (modular monolith)

```
                      ┌─────────────────────────── PayGuard (one Spring Boot app) ───────────────────────────┐
                      │                                                                                     │
 Customer / Operator ─┼─► POST /api/checkout ──► CheckoutService ──HTTP──► /sim/gateway/** (Gateway Simulator) │
                      │        │  idempotency_records                         │ gateway.* schema            │
                      │        ▼                                              │ (authoritative state)        │
                      │   orders, payments                                    │                             │
                      │                                                       │ signed webhook (HTTP)       │
                      │                                                       ▼                             │
                      │                     POST /api/webhooks/{provider} ◄───┘                             │
                      │                        verify HMAC → validate → INSERT … ON CONFLICT → COMMIT → 200   │
                      │                        webhook_events / webhook_rejections                          │
                      │                                                                                     │
                      │   Phase 2: outbox_events ─► Outbox Publisher ─► RabbitMQ ─► Worker ─► payments/orders │
                      │   Phase 3: Reconciliation (merchant DB vs gateway vs settlement reports)            │
                      └─────────────────────────────────────────────────────────────────────────────────────┘
                                                         PostgreSQL 16
```

Why a **modular monolith**, not microservices: one deployable, one database, real transactions.
Every reliability problem we want to demonstrate (duplicates, crashes, ordering) still exists,
because the merchant talks to the gateway **over HTTP** and (Phase 2) to the worker **over RabbitMQ**.
Microservices would add network hops and ops cost without teaching anything new. See ADR-002.

The gateway simulator lives in its **own schema** (`gateway`) and the merchant code never reads those
tables — only its HTTP API. In production the simulator is switched off (`payguard.simulator.enabled=false`,
the default).

## 3. Request flows (Phase 1)

### 3.1 Checkout (API idempotency)
```
client ──POST /api/checkout (Idempotency-Key: K)──► claim(K)
   claim = INSERT idempotency_records … ON CONFLICT DO NOTHING
   ├─ new           → TX1: insert order + payment, recovery_point = ORDER_CREATED   (COMMIT)
   │                  HTTP: gateway create payment, Idempotency-Key = "pgk_" + paymentId  (no DB tx open)
   │                  TX2: link gateway id, store response, status = COMPLETED     (COMMIT) → 201
   ├─ completed     → return the stored response byte-for-byte (Idempotent-Replayed: true)
   ├─ in progress   → 409 IDEMPOTENCY_REQUEST_IN_PROGRESS (client retries later)
   ├─ lease expired → take over (new fencing token) and RESUME from recovery_point
   └─ other body    → 422 IDEMPOTENCY_KEY_REUSED
```

### 3.2 Gateway state change → webhook
```
POST /sim/gateway/payments/{id}/capture
   BEGIN; SELECT … FOR UPDATE; state machine check; UPDATE (version+1); INSERT gateway_events; COMMIT
   AFTER COMMIT (async): sign "t.body" with HMAC-SHA256 → POST merchant webhook → log delivery
```

### 3.3 Webhook ingestion
```
raw bytes → size check → HMAC verify (provider adapter) → schema validation
   fail → webhook_rejections (never webhook_events) → 401 / 400
   ok   → BEGIN; INSERT webhook_events … ON CONFLICT (provider, provider_event_id) DO NOTHING;
          if no row: UPDATE duplicate_count+1;  COMMIT  → 200 {ACCEPTED | DUPLICATE | IGNORED}
```

## 4. Payment states and settlement

```
CREATED ──► AUTHORIZED ──► CAPTURED ──► PARTIALLY_REFUNDED ──► REFUNDED
   │            │              │   ▲          │   ▲   (repeat partial refunds)
   └──► FAILED ◄┘              │   └──────────┘   │
                               └──► REFUND_PENDING┘
```
* Terminal: `FAILED`, `REFUNDED`. Same-state request = `NO_OP` (that is what makes retries harmless).
* **Settlement is not a payment state.** Captured = customer charged. Settled = money reached our bank
  account, days later, in a batch report. It gets its own table (`settlement_records`, Phase 3).
* Each gateway state change bumps `version`; webhooks carry it. The merchant will use it (Phase 2/3)
  to drop stale events: an event with `version <= stored version` can never move state backwards.

## 5. System invariants (what must ALWAYS be true)

| # | Invariant | Enforced by | Phase |
|---|-----------|-------------|-------|
| I1 | An acknowledged (2xx) webhook is durably stored | 200 returned only after `COMMIT` | 1 ✔ |
| I2 | One provider event id is stored at most once | `UNIQUE (provider, provider_event_id)` | 1 ✔ |
| I3 | Invalid signatures never mutate any business state | verification before parsing; separate `webhook_rejections` table | 1 ✔ |
| I4 | Money is an integer number of minor units; fractions are rejected, never rounded | `BIGINT` + `CHECK > 0` + Jackson `accept-float-as-int=false` + parser checks | 1 ✔ |
| I5 | One idempotency key ⇒ at most one order and one gateway payment | `UNIQUE (scope, key)` + deterministic gateway key | 1 ✔ |
| I6 | Refunds never exceed the captured amount | row lock + `CHECK (refunded BETWEEN 0 AND amount)` | 1 ✔ |
| I7 | No duplicate fulfilment for the same order | `UNIQUE(order_id)` on fulfilments + `ON CONFLICT DO NOTHING` | 2 ✔ |
| I8 | Committed events remain recoverable | transactional outbox; outbox never gives up | 2 ✔ |
| I9 | Stale events cannot regress payment state | `gateway_version` comparison + reachability | 2 ✔ |
| I10 | Reconciliation never silently overwrites ambiguous states | auto-fix allow-list via the guarded applier, otherwise OPEN | 3 ✔ |
| I11 | Messages are acked only after the DB commit | manual ack in `PaymentEventListener` | 2 ✔ |
| I12 | Every manual intervention is audited and immutable | `audit_logs` + DB trigger rejecting UPDATE/DELETE | 2 ✔ |

✔ = implemented in code; see `testing-results.md` for which checks have actually been executed.

## 6. Folder structure

```
payguard/
├── backend/                         Spring Boot 3.5, Java 21, Maven
│   ├── src/main/java/com/payguard/
│   │   ├── common/                  crypto (HMAC, SHA-256, fingerprint), errors, ids
│   │   ├── config/                  security, clock, executors
│   │   ├── domain/payment/          PaymentStatus + PaymentStateMachine (shared, pure Java)
│   │   ├── gateway/                 Module A: gateway simulator (own DB schema "gateway")
│   │   ├── idempotency/             Module C: persistent API idempotency
│   │   ├── merchant/                orders, payments (+ checkout/: API + gateway client)
│   │   ├── webhook/                 Module B: ingestion, parser, store
│   │   │   ├── signature/           HMAC header format (shared wire protocol)
│   │   │   └── verification/        provider adapters (WebhookVerifier)
│   │   ├── outbox/                  transactional outbox store + publisher (SKIP LOCKED, confirms)
│   │   ├── messaging/               RabbitMQ topology (exchange, queue, DLX, DLQ)
│   │   ├── worker/                  consumer, processor, retry policy, failure handler, dead letters
│   │   ├── reconciliation/          rules (pure), service, store, controller + scheduler
│   │   ├── audit/                   append-only audit log
│   │   ├── faults/                  fault injection (demo only)
│   │   ├── simulation/              12 failure scenarios + recorder
│   │   └── ops/                     dashboard / health / timeline API
│   ├── src/main/resources/db/migration/   Flyway V1..Vn
│   └── src/test/
│       ├── java/…                   *Test = unit (surefire), *IT = integration (failsafe)
│       └── sql/                     schema_checks.sql, pgbench concurrency script
├── frontend/                        React + TS + Vite + Tailwind dashboard (+ Playwright e2e)
├── docs/                            design, ADRs, interview guide, test results
├── .github/workflows/ci.yml
├── docker-compose.yml
└── PROJECT_PROGRESS.md
```

## 7. Implementation plan

| Phase | Scope | Exit criteria |
|-------|-------|---------------|
| **1 Core MVP** | Gateway simulator, checkout API, webhook receiver, HMAC, durable storage, API idempotency, tests | `mvn verify` green in CI; schema + concurrency checks green |
| **2 Reliable processing** | Outbox table written in the webhook TX; publisher (`FOR UPDATE SKIP LOCKED`, publisher confirms); RabbitMQ worker with manual ack after commit; idempotent fulfilment; retry with backoff + jitter; DLQ + audited replay | Crash-before-commit / crash-after-commit / broker-down tests pass |
| **3 Correctness** | Stale/out-of-order handling with versions; reconciliation (merchant vs gateway vs settlement); safe auto-recovery allow-list; audit log | Missing-webhook and amount-mismatch scenarios detected and recovered/flagged |
| **4 Differentiators** | Failure-injection engine (12 scenarios, demo profile only); Micrometer metrics; React dashboard; interview demo mode; load tests | Every simulation runs real backend logic and shows DB evidence |
| **5 Final quality** | Security review, rate limiting, JSON logs, full docs, demo video script | Acceptance criteria in the master prompt §12 |

## 8. Runtime topology (docker-compose)

| Process | Role | Key settings |
|---------|------|--------------|
| `backend` | HTTP API, webhook ingestion, outbox publisher, gateway simulator, reconciliation scheduler, failure lab | `PAYGUARD_WORKER_ENABLED=false` |
| `worker` ×2 | RabbitMQ consumers only (no web server) | `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, publisher/simulator/reconciliation off |
| `frontend` | nginx serving the dashboard and proxying `/api` | same origin → no CORS |
| `postgres`, `rabbitmq` | state and messaging | durable volumes |

The same jar runs in both roles; CI's e2e job starts exactly this split (API + separate worker).
