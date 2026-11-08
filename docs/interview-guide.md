# PayGuard — Interview Guide

How to use this: for each feature, be able to say the **one-line problem**, draw the **flow**, point to the
**exact code**, and answer the **"what if it crashes here?"** question. Sections grow with each phase.

---

## Feature 1 — Webhook signature verification (HMAC-SHA256)

**1. Problem.** Our webhook URL is public. Anyone can POST "payment pay_123 CAPTURED" to it and get free goods.

**2. Without it.** A forged request marks an unpaid order as paid → we ship for free.

**3. Implementation.** `WebhookSignature.verify` (`webhook/signature/WebhookSignature.java`):
* header `t=<unix>,v1=<hex>`; signed bytes = `"<t>." + rawBody`;
* compute `HMAC-SHA256(secret, signedBytes)` and compare with **`MessageDigest.isEqual`** (constant time);
* then check `|now − t| ≤ 5 min`.
The controller binds the body as `byte[]` (`WebhookController.receive`) so we verify the exact wire bytes.

**4. Why HMAC.** Symmetric, fast, standard (Stripe, Razorpay, GitHub all use it). Only the gateway and we know the secret.

**5. Alternatives.** Asymmetric signatures (gateway signs with a private key — no shared secret to leak; slower,
key distribution needed); mTLS; IP allow-listing (weak alone: IPs change, can be spoofed in some setups).

**6. Trade-offs.** A shared secret must be stored safely and rotated → we accept several secrets at once.

**7. If it crashes.** Verification is stateless; nothing to recover.

**8. DB concepts.** Rejections go to a separate table (`webhook_rejections`) so forged ids never enter the dedup key space.

**9. Concurrency.** None in the check itself. (Timing side channels are the "concurrency-like" risk → constant-time compare.)

**10. Interview questions.**
* *Why not `String.equals` for the signature?* It returns at the first different character, so response time leaks
  how many leading characters an attacker guessed right. `MessageDigest.isEqual` always compares every byte.
* *Why is the timestamp inside the signed data?* So an attacker cannot take an old valid request and just change `t=`.
  `WebhookSignatureTest.changingTheTimestampBreaksTheSignature` proves it.
* *Why verify raw bytes and not the parsed JSON?* `{"a":1}` and `{"a": 1}` mean the same thing but are different
  bytes → different HMAC. Any parse-then-reserialise step breaks verification
  (`WebhookSignatureTest.whitespaceChangesAlsoBreakTheSignature`).
* *Replay attack?* Inside the 5-minute window the replay passes the signature but is deduplicated by event id;
  outside it is rejected as `TIMESTAMP_OUT_OF_TOLERANCE`.
* *How do you rotate the secret without downtime?* Configure old + new secret, gateway switches, remove old.

---

## Feature 2 — Durable ingestion + event deduplication

**1. Problem.** Gateways deliver **at least once**: retries after timeouts mean the same event can arrive 2, 5, 50 times.
We must store each event exactly once and never lose one we acknowledged.

**2. Without it.** Acknowledge-then-store: a crash after "200 OK" loses the event forever (gateway stops retrying).
No dedup: a duplicate "captured" could trigger a second shipment.

**3. Implementation.** `WebhookIngestionService.ingest` → `WebhookEventStore.insertOrMarkDuplicate`:
```sql
INSERT INTO webhook_events (provider, provider_event_id, …) VALUES (…)
ON CONFLICT (provider, provider_event_id) DO NOTHING
RETURNING id;                                   -- no row returned ⇒ duplicate
UPDATE webhook_events SET duplicate_count = duplicate_count + 1 … WHERE provider = ? AND provider_event_id = ?;
```
Run inside `TransactionTemplate.execute(...)`; the HTTP 200 is built only after `execute` returns, i.e. after COMMIT.

**4. Why PostgreSQL unique constraint.** The database is the only component that sees *all* concurrent requests.
Application-level "check then insert" has a race window.

**5. Alternatives.** Redis `SETNX` (fast, but a second source of truth that can disagree with the DB, and keys can be
evicted); in-memory set (lost on restart, wrong with >1 instance); `SELECT` then `INSERT` (racy).

**6. Trade-offs.** One indexed write per delivery; the unique index grows → retention policy (archive raw bodies after 90 days).

**7. If it crashes.** Before COMMIT → no row, no 200 → gateway retries → stored next time. After COMMIT but before
the 200 reaches the gateway → gateway retries → we answer `DUPLICATE`. **Either way: nothing lost, nothing doubled.**

**8. DB concepts.** Unique index, `INSERT … ON CONFLICT`, READ COMMITTED snapshots per statement, row locks on UPDATE.

**9. Concurrency.** 1,000 identical deliveries at once: the first INSERT holds the new index entry; the others
**wait** for that transaction, then hit the conflict. The follow-up UPDATE is a new statement with a fresh snapshot,
so it sees the committed row; the row lock serialises the counter increments (no lost updates).
Measured at DB level: 1 row, `duplicate_count = 999`, 0 failures (`testing-results.md` #3).

**10. Interview questions.**
* *Why store `raw_body` and `payload` both?* JSONB normalises (key order, whitespace); the raw text is the exact signed
  content for audits/re-verification.
* *Why 200 for a duplicate and not 409?* Gateways treat non-2xx as "retry". A duplicate is a success from their point of view.
* *What is "exactly once"?* Over a network, delivery is at-least-once; we get **exactly-once effect** = at-least-once
  delivery + idempotent processing.

---

## Feature 3 — API request idempotency (checkout)

**1. Problem.** The client sends "create order", the network drops the response, the client retries. Two orders? Two charges?

**2. Without it.** Every retry creates a new order and a new gateway payment → customer charged twice.

**3. Implementation.** `IdempotencyService.claim` + `CheckoutService` + `idempotency_records`:
* `INSERT … ON CONFLICT DO NOTHING` claims the key; otherwise `SELECT … FOR UPDATE` decides:
  **Replay** (completed), **InFlight** (lease valid → 409), **FingerprintMismatch** (→ 422), **Acquired** (takeover after lease expiry).
* The work is split into steps with a durable `recovery_point`: TX1 creates order+payment and sets `ORDER_CREATED`
  in the **same transaction**; the gateway HTTP call runs with **no DB transaction open**; TX2 links and stores the response.
* **Fencing token**: every write has `WHERE lease_token = :token`. A slow "zombie" request whose lease expired gets
  0 rows updated and aborts (`IDEMPOTENCY_LEASE_LOST`) instead of overwriting the new owner.
* **Deterministic downstream key**: gateway `Idempotency-Key = "pgk_" + paymentId` (`CheckoutService.gatewayKeyFor`).

**4. Why this design.** It is the pattern Stripe describes publicly for its own API (idempotency keys + recovery points).

**5. Alternatives.** One big transaction around the HTTP call (holds a connection + locks while waiting on the network
and *still* cannot roll back the gateway); client-generated order ids with a unique constraint (simpler, but no stored
response to replay and no protection for the downstream call).

**6. Trade-offs.** More states to reason about; lease length is a tuning knob (too short → takeovers of slow-but-alive
requests, handled safely by fencing; too long → clients wait after a crash).

**7. If it crashes.** After TX1 → retry (after lease expiry) resumes at `ORDER_CREATED`, no second order. After the
gateway call → retry sends the same gateway key → gateway returns the payment it already created
(`CheckoutIT.retryAfterCrashResumesAndDoesNotCreateASecondGatewayPayment`).

**8. DB concepts.** Unique constraints, `SELECT … FOR UPDATE`, transaction propagation `MANDATORY`
(`advance`/`complete` must join the caller's TX), TTL cleanup in batches.

**9. Concurrency.** 100 simultaneous requests with one key → one owner; others get 409 or the replay; DB shows exactly
one order and one gateway payment (`CheckoutIT.hundredConcurrentRetriesCreateOneOrder`).

**10. Interview questions.**
* *Same key, different body?* 422 — silently returning the old result would hide a client bug.
* *Why is the fingerprint computed from fields and not the raw JSON?* Key order/whitespace should not matter; fields are
  length-prefixed so `("ab","c")` and `("a","bc")` don't collide.
* *What's a fencing token?* A number/UUID that changes with every new owner; storage rejects writes carrying an old one.

---

## Feature 4 — Gateway simulator: state machine + row locks

**1. Problem.** "Capture" can be clicked twice, retried after a timeout, or sent for a failed payment.

**3. Implementation.** `PaymentStateMachine` (one `EnumMap` table, decisions `APPLY / NO_OP / REJECT`);
`GatewayPaymentService.transition`: `SELECT … FOR UPDATE` → decide → update (`@Version` bumps) → insert one
`gateway_events` row; webhook sent by `@TransactionalEventListener(AFTER_COMMIT)`.

**7. If it crashes.** Between commit and webhook send, the event exists but is never sent automatically — the realistic
"missing webhook" that **reconciliation** (Phase 3) must detect. We kept this gap on purpose.

**9. Concurrency.** 50 concurrent captures → the row lock makes them run one after another: 1 `APPLY`, 49 `NO_OP`,
exactly one `payment.captured` event (`GatewaySimulatorIT.concurrentCapturesProduceOneCaptureEvent`).
10 concurrent 60 % refunds → exactly one succeeds.

**Interview questions.**
* *Pessimistic or optimistic locking here?* Pessimistic (`FOR UPDATE`): conflicts on one payment are expected (retries),
  and waiting is cheaper than failing and retrying. Optimistic `@Version` stays as a second safety net.
* *Why send the webhook after commit?* If we sent it inside the TX and then rolled back, the merchant would have heard
  about a capture that never happened.

---

## The three idempotencies (common interview trap)

| Layer | Question it answers | Key | Where |
|-------|--------------------|-----|-------|
| API request idempotency | "Is this the same *request* retried?" | client's `Idempotency-Key` | `idempotency_records` |
| Webhook event dedup | "Is this the same *event* delivered again?" | gateway's event id | `webhook_events` unique |
| Idempotent fulfilment | "Has this *order* already been shipped?" | order id | `fulfilments` unique (Phase 2) |

Why all three: a gateway can send **two different event ids** that both mean "captured" (e.g. a re-emitted event), and
reconciliation can also discover the capture. Event dedup can't catch that — only the order-level check can.
