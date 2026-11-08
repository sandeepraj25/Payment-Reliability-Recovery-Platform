# PayGuard — Database Design (PostgreSQL 16)

Flyway owns the schema (`src/main/resources/db/migration`). Hibernate runs with `ddl-auto: validate`:
it may only *check* that entities match, never change tables.

General rules
* **Money**: `BIGINT` minor units (paise) + `CHECK (amount > 0)`. Never `NUMERIC` with fractions, never float.
  Gotcha found while validating: PostgreSQL silently **rounds** `4999.50` into a `BIGINT` column (stores 5000),
  so the database is *not* the guard against fractional money — the API/parser layer is (see `schema_checks.sql`).
* **Timestamps**: `TIMESTAMPTZ`, stored in UTC.
* **Status columns**: `VARCHAR` + `CHECK (status IN (…))` rather than PostgreSQL enums — adding a value is a
  simple constraint swap, and Java enums map with `@Enumerated(STRING)`.
* **Uniqueness is the source of truth for "exactly once"**: application `if (!exists) insert` checks are
  racy; unique constraints are not.

---

## Implemented tables (Phase 1)

### `gateway.gateway_payments` — V1
| | |
|---|---|
| Purpose | The simulated gateway's **authoritative** record of each payment (what Razorpay would know). |
| PK | `id` (`pay_…`, random base62) |
| FKs | — (separate "company"; links to merchant only via `merchant_order_ref`) |
| Unique | `gateway_reference`; `create_idempotency_key` (one key ⇒ one payment) |
| Checks | amount > 0; `0 ≤ amount_refunded ≤ amount`; currency `^[A-Z]{3}$`; status list; version ≥ 0 |
| Indexes | `merchant_order_ref` (reconciliation lookups) |
| Transactions | Every state change: `SELECT … FOR UPDATE` → update (version+1) → insert event, one TX |
| Retention | Keep for the life of the demo; real gateways keep years (regulatory). |

### `gateway.gateway_refunds` — V1
| | |
|---|---|
| Purpose | One row per refund (each refund is a separate financial effect). |
| PK / FK | `id` / `payment_id → gateway_payments` |
| Unique | `(payment_id, idempotency_key)` — a retried "refund ₹500" can never refund ₹1000 |
| Transactions | Same TX as the payment row update; payment row locked first |

### `gateway.gateway_events` — V1
| | |
|---|---|
| Purpose | Every gateway state change produces exactly one event (the gateway's own outbox). |
| Unique | `(payment_id, payment_version)` — two events for one version are impossible |
| Notes | `payload` JSONB is what gets signed and sent. Written in the state-change TX; sent **after** commit. |

### `gateway.webhook_deliveries` — V1
Delivery log for every HTTP attempt (status, error, duration, AUTO vs MANUAL_REDELIVERY). Evidence for
"did the gateway really send it?". Index `(event_id, attempted_at)`. Retention: 30 days is plenty.

### `orders` — V2
| | |
|---|---|
| Purpose | Merchant's order. |
| PK | `id` UUID |
| Checks | amount > 0; currency; status list; `(status = 'FULFILLED') = (fulfilled_at IS NOT NULL)` |
| Indexes | `(status, created_at)` — "pending orders older than X" (reconciliation, dashboard) |
| Locking | `version` column → JPA optimistic locking |

### `payments` — V2
| | |
|---|---|
| Purpose | Merchant's view of one payment **attempt** (an order may have several). Can lag the gateway — by design. |
| PK / FK | `id` UUID / `order_id → orders` |
| Unique | `(provider, gateway_payment_id)` — NULLs allowed (not yet registered), real ids map to one row |
| Deliberately NOT unique | "one CAPTURED payment per order": real double charges happen and must be *recordable* so reconciliation can refund them. We prevent double **fulfilment** instead. |
| Indexes | `order_id` |

### `webhook_events` — V3
| | |
|---|---|
| Purpose | Every **authentic** webhook accepted. |
| PK | `id` BIGSERIAL |
| Unique | `(provider, provider_event_id)` — the dedup guarantee, holds under 1,000 concurrent deliveries (measured) |
| Columns | `raw_body` TEXT (exact signed bytes, for audit/re-verification) and `payload` JSONB (queryable; normalised by PG) |
| Checks | status list; known types must carry `gateway_payment_id` (only IGNORED may omit it) |
| Indexes | `gateway_payment_id`; `received_at DESC` |
| Transactions | Insert-or-count-duplicate in one TX, committed before the HTTP 200. Phase 2 adds the outbox insert to the same TX. |
| Retention | Raw bodies 90 days, then archive; keep id/type/status longer for audit. |

### `webhook_rejections` — V3
| | |
|---|---|
| Purpose | Requests that failed verification or validation. |
| Why separate | If forged requests went into `webhook_events`, an attacker could pre-register `evt_123` and the real `evt_123` would be dropped as a "duplicate". Unverified data must never occupy the dedup key space. (Covered by `WebhookIngestionIT.forgedEventCannotSquatOnARealEventId`.) |
| Notes | Body excerpt truncated to 500 chars, untrusted. Index on `received_at`. Retention: 14–30 days; rate limiting (Phase 5) caps write volume. |

### `idempotency_records` — V4
| | |
|---|---|
| Purpose | API request idempotency for `POST /api/checkout`. |
| Unique | `(scope, idempotency_key)` |
| Key columns | `request_fingerprint` (same key + different body ⇒ 422); `lease_token` + `locked_until` (crash-safe lease with **fencing**); `recovery_point` (resume, don't redo) |
| Checks | `COMPLETED` ⇒ response stored |
| Indexes | `expires_at` (cleanup job deletes in batches of 1,000) |
| Retention | TTL 24 h (`payguard.idempotency.ttl`) |

---

## Designed, not yet migrated

### `outbox_events` — Phase 2 (V5)
```sql
CREATE TABLE outbox_events (
  id               BIGSERIAL PRIMARY KEY,
  webhook_event_id BIGINT NOT NULL REFERENCES webhook_events(id),
  event_type       VARCHAR(64) NOT NULL,
  payload          JSONB NOT NULL,
  status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',     -- PENDING | PUBLISHED | FAILED
  attempts         INT NOT NULL DEFAULT 0,
  next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error       VARCHAR(500),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at     TIMESTAMPTZ,
  CONSTRAINT uq_outbox_webhook_event UNIQUE (webhook_event_id)
);
-- Partial index: the publisher only ever scans pending rows.
CREATE INDEX ix_outbox_pending ON outbox_events (next_attempt_at) WHERE status = 'PENDING';
```
Publisher: `SELECT … WHERE status='PENDING' AND next_attempt_at <= now() ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`,
publish with confirms, mark PUBLISHED. Ambiguous confirm ⇒ leave PENDING ⇒ republish ⇒ consumer dedups (at-least-once).

### `fulfilments` — Phase 2 (idempotent business effect)
`UNIQUE (order_id)` + `INSERT … ON CONFLICT DO NOTHING`. Two *different* events that both mean "paid"
(e.g. a webhook and a reconciliation recovery) still ship once.

### `processing_attempts` — Phase 2
`(webhook_event_id FK, attempt_no)` unique; outcome `SUCCESS | TRANSIENT_FAILURE | PERMANENT_FAILURE | SKIPPED_STALE`,
error class/message, worker id, duration. Retry history + DLQ evidence. Retention 30 days.

### `reconciliation_runs` / `reconciliation_mismatches` — Phase 3
Run: id, started/finished, window, counts, status. Mismatch: `run_id FK`, gateway/merchant payment refs,
`mismatch_type` (GATEWAY_CAPTURED_MERCHANT_PENDING, MERCHANT_CAPTURED_GATEWAY_NOT, AMOUNT_MISMATCH,
MISSING_SETTLEMENT, DELAYED_SETTLEMENT, …), `evidence JSONB`, `resolution` (OPEN | AUTO_RESOLVED | MANUAL_REVIEW | RESOLVED).
Unique `(run_id, gateway_payment_id, mismatch_type)`.

### `refund_records` — Phase 3
Merchant-side refund requests with their own idempotency key → gateway refund id. Unique `(gateway_refund_id)`.

### `settlement_records` — Phase 3
Rows from the simulated settlement report: `settlement_batch_id`, `gateway_payment_id`, gross, fee, net, `settled_at`.
Unique `(settlement_batch_id, gateway_payment_id)`. Captured ≠ settled.

### `audit_logs` — Phase 3
Append-only: actor, action, target, before/after JSONB, reason, correlation id. The app's DB role gets
`INSERT, SELECT` only (`REVOKE UPDATE, DELETE`) so history cannot be rewritten by the app.
