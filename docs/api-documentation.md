# API Documentation (Phase 1)

All errors use one shape:
```json
{ "code": "IDEMPOTENCY_KEY_REUSED", "message": "human readable", "details": [], "timestamp": "2026-11-08T10:00:00Z" }
```
`code` is stable and machine-readable; never match on `message`.

Auth: HTTP Basic. `ADMIN` for `/api/**` (except webhooks); `GATEWAY_CLIENT` or `ADMIN` for `/sim/gateway/**`.
Webhooks are authenticated by HMAC signature, not by login.

---

## Merchant API

### `POST /api/checkout`
Headers: `Idempotency-Key` (8–255 chars, required). Body:
```json
{ "customerRef": "cust_1", "amountMinor": 499900, "currency": "INR" }
```
`amountMinor` must be a JSON integer (paise). `4999.5` → 400.

| Status | Meaning |
|--------|---------|
| 201 | Created — or a replay of the original 201 (`Idempotent-Replayed: true`, identical body) |
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `MISSING_HEADER` |
| 409 | `IDEMPOTENCY_REQUEST_IN_PROGRESS` — retry shortly with the same key |
| 422 | `IDEMPOTENCY_KEY_REUSED` — same key, different body |
| 502 / 503 | `GATEWAY_ERROR` / `GATEWAY_UNAVAILABLE` — safe to retry with the same key |

Response:
```json
{ "orderId": "…uuid…", "paymentId": "…uuid…", "gatewayPaymentId": "pay_…", "amountMinor": 499900,
  "currency": "INR", "orderStatus": "PAYMENT_PENDING", "paymentStatus": "CREATED" }
```

### `GET /api/orders/{orderId}`
Order with all payment attempts.

### `GET /api/webhook-events?gatewayPaymentId=&limit=50` · `GET /api/webhook-events/{id}` · `GET /api/webhook-rejections?limit=50`
Operator views (limit clamped to 1–500).

---

## Webhook endpoint

### `POST /api/webhooks/{provider}`
Provider `payguard-sim`. Headers: `PayGuard-Signature: t=<unix seconds>,v1=<hex hmac>`, optional
`PayGuard-Event-Id` (must equal body `id`). Signed payload: `<t>.<raw body bytes>`, HMAC-SHA256.

| Status | Body / code | Gateway should… |
|--------|------------|-----------------|
| 200 | `{"result":"ACCEPTED"\|"DUPLICATE"\|"IGNORED","eventId":"evt_…","webhookEventId":42}` | stop retrying |
| 400 | `MALFORMED_PAYLOAD` | (fix its payload) |
| 401 | `MISSING_SIGNATURE`, `MALFORMED_SIGNATURE`, `INVALID_SIGNATURE`, `TIMESTAMP_OUT_OF_TOLERANCE` | — |
| 404 | unknown provider | — |
| 413 | `PAYLOAD_TOO_LARGE` (> 64 KiB) | — |
| 5xx | storage failed — **nothing was acknowledged** | retry |

Event body:
```json
{ "id": "evt_…", "type": "payment.captured", "created_at": "2026-11-08T10:00:00Z",
  "data": { "payment": { "id": "pay_…", "gateway_reference": "grf_…", "merchant_order_ref": "…",
            "amount_minor": 499900, "amount_refunded_minor": 0, "currency": "INR",
            "status": "CAPTURED", "version": 2 } } }
```
Known types: `payment.authorized`, `payment.captured`, `payment.failed`, `payment.refunded` (with `data.refund`).

---

## Gateway simulator (local/demo only; `PAYGUARD_SIMULATOR_ENABLED=true`)

| Method & path | Notes |
|---------------|-------|
| `POST /sim/gateway/payments` | `Idempotency-Key` required. Body `{merchantOrderRef, amountMinor, currency}`. 201 new / 200 replay. |
| `GET /sim/gateway/payments/{id}` | Authoritative status API |
| `POST /sim/gateway/payments/{id}/authorize` · `/capture` · `/fail` | State-machine checked; repeat = no-op (no new event); illegal = 409 `INVALID_STATE_TRANSITION` |
| `POST /sim/gateway/payments/{id}/refunds` | `Idempotency-Key` required. Body `{amountMinor}`. 422 `REFUND_EXCEEDS_REMAINING` |
| `GET /sim/gateway/payments/{id}/refunds` · `/events` | History |
| `POST /sim/gateway/events/{eventId}/redeliver` | Re-sends a webhook → duplicate delivery on demand |
| `GET /sim/gateway/events/{eventId}/deliveries` | Delivery attempts log |

## Quick demo with curl
```bash
A="-u admin:$PAYGUARD_ADMIN_PASSWORD -H Content-Type:application/json"
curl $A -H 'Idempotency-Key: demo-key-0001' -d '{"customerRef":"cust_1","amountMinor":499900,"currency":"INR"}' localhost:8080/api/checkout
PAY=pay_xxx   # gatewayPaymentId from the response
curl $A -X POST localhost:8080/sim/gateway/payments/$PAY/authorize
curl $A -X POST localhost:8080/sim/gateway/payments/$PAY/capture
curl $A "localhost:8080/api/webhook-events?gatewayPaymentId=$PAY"      # 2 events, signed + stored
EVT=$(curl -s $A localhost:8080/sim/gateway/payments/$PAY/events | jq -r '.[1].id')
curl $A -X POST localhost:8080/sim/gateway/events/$EVT/redeliver      # duplicate → duplicate_count = 1
```

---

## Phase 2–5 endpoints

All `GET /api/**` allow `ADMIN` or `VIEWER`; every other method needs `ADMIN`. Errors: same `ApiError` shape.
Rate limit: 429 `RATE_LIMITED` with `Retry-After`. Every response carries `X-Correlation-Id`.

### Session
| `GET /api/me` | `{username, roles[], faultsEnabled}` |
|---|---|

### Processing & dead letters
| Method & path | Notes |
|---|---|
| `GET /api/webhook-events?status=&eventId=&gatewayPaymentId=&limit=` | now includes `attemptCount`, `processingResult`, `lastError` |
| `GET /api/webhook-events/{id}/attempts` | retry history (outcome, worker, redelivered, error, retry delay) |
| `GET /api/webhook-events/{id}/outbox` | outbox rows (INGEST / RETRY / REPLAY) |
| `GET /api/dead-letters` | `{events[], brokerDlqDepth}` |
| `POST /api/dead-letters/{webhookEventId}/replay` | body `{"reason": "…"}` (required, audited). 409 `NOT_DEAD_LETTERED` unless DEAD |

### Reconciliation
| Method & path | Notes |
|---|---|
| `POST /api/reconciliation/runs` | runs synchronously, returns the run. 409 `RECONCILIATION_ALREADY_RUNNING` |
| `GET /api/reconciliation/runs?limit=` · `GET /api/reconciliation/runs/{id}` | |
| `GET /api/reconciliation/mismatches?runId=&resolution=&limit=` | resolution ∈ OPEN, AUTO_RESOLVED, ALREADY_CONSISTENT, INFORMATIONAL, RESOLVED_MANUALLY |
| `POST /api/reconciliation/mismatches/{id}/resolve` | body `{"note": "…"}` (required, audited). 409 `NOT_OPEN` |
| `GET /api/reconciliation/runs/{id}/report.csv` | RFC 4180 CSV, formula-injection safe |

### Operations / dashboard
| Method & path | Notes |
|---|---|
| `GET /api/ops/summary` | counts by status, backlog, queue/DLQ depth, open mismatches, last run |
| `GET /api/ops/timeseries` | 60 one-minute buckets: received, rejected, processed, failed, skipped |
| `GET /api/ops/health` | DB/RabbitMQ status, workers (from DB heartbeat), outbox, latency percentiles |
| `GET /api/payments?status=&q=&limit=` | `q` = payment id, order id or gateway id |
| `GET /api/payments/{id}/timeline` | merchant + live gateway view + merged lifecycle + audit |
| `GET /api/audit?targetType=&targetId=&limit=` | audit trail |
| `GET /actuator/prometheus` | ADMIN only |

### Failure simulation lab (only when `PAYGUARD_FAULTS_ENABLED=true` and simulator enabled; otherwise 404)
| Method & path | Notes |
|---|---|
| `GET /api/simulations/scenarios` | the 12 scenarios with explanations |
| `POST /api/simulations/{SCENARIO}/runs` | starts in background → `{runId, status: RUNNING}` |
| `GET /api/simulations/runs/{id}` | `status` RUNNING/PASSED/FAILED/ERROR, `steps` (JSON timeline with evidence), `invariants` |
| `GET /api/simulations/runs?scenario=&limit=` | history |
| `GET /api/faults` · `POST /api/faults/{POINT}` `{"times": n}` · `DELETE /api/faults` | low-level fault switches |

### Gateway simulator additions
| `GET /sim/gateway/payments?updatedSince=ISO&afterId=&limit=` | keyset-paginated status list (used by reconciliation) |
|---|---|
| `POST /sim/gateway/settlements/run` `{"cutoff": ISO?}` | creates a settlement batch (2% fee, integer maths) |
| `GET /sim/gateway/settlements?since=ISO` | settlement reports |
