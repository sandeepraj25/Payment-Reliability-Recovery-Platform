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

---

## Feature 5 — Transactional outbox (Module D)

**1. Problem.** "Save to DB **and** send to RabbitMQ" is a *dual write*: two systems, no shared transaction.
Crash between the two and they disagree forever.

**2. Without it.** Commit then publish → crash in between → event stored but never processed (customer paid,
nothing ships). Publish then commit → crash → worker processes an event the DB never stored.

**3. Implementation.** `WebhookIngestionService.ingest`: the `webhook_events` row **and** the `outbox_events` row are
inserted in the same `TransactionTemplate` → commit together or not at all. `OutboxPublisher.publishOnce`:
```sql
SELECT ... FROM outbox_events WHERE status='PENDING' AND next_attempt_at <= :now
 ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
```
→ publish all → `waitForConfirmsOrDie` → `UPDATE ... status='PUBLISHED'` → commit.

**4. Why.** It turns two writes into one local ACID write plus a retryable copy job. No distributed transaction (2PC)
needed, and RabbitMQ doesn't support XA anyway.

**5. Alternatives.** CDC (Debezium reading the WAL — no polling, but another moving part); "listen to yourself"
(publish first, consume your own message to write the DB); 2PC/XA (slow, fragile, unsupported here).

**6. Trade-offs.** Polling latency (500 ms here); outbox table growth (index is *partial* on PENDING only); duplicates are
possible, so consumers must be idempotent.

**7. If it crashes.** Before commit → rows still PENDING, locks released → next poll republishes. After broker confirm
but before marking PUBLISHED → **the same message is published twice** → the worker sees PROCESSED → skip.
Tested: `FailureRecoveryIT.outboxPublisherCrashAfterBrokerConfirmRepublishesAndTheWorkerDeduplicates`.

**8. DB concepts.** Row locks, `SKIP LOCKED`, partial indexes, at-least-once.

**9. Concurrency.** Two publishers never send the same row at once (`SKIP LOCKED` skips rows locked by the other
instead of waiting). Tested with 300 rows and 4 extra racing publishers: every row published exactly once
(`OutboxIT.concurrentPublishersNeverSendTheSameRowTwice`).

**10. Questions.** *"Is it exactly-once?"* No — at-least-once publishing + idempotent consumer = exactly-once
**effect**. *"What if RabbitMQ is down for an hour?"* Webhooks are still acknowledged (durable in PostgreSQL); the
outbox backs off (0.5 s → 30 s) and drains when the broker returns — never gives up.

---

## Feature 6 — Reliable worker: ack after commit (Module E)

**3. Implementation.** `PaymentEventListener` (manual ack) → `EventProcessor.process` (`@Transactional`):
lock `webhook_events` row → lock `payments` row → stale check → apply → lock `orders` row → fulfil once → mark
PROCESSED + attempt row → **COMMIT** → only then `basicAck`.

**7. If it crashes.**

| Crash point | What happens | Test |
|---|---|---|
| before COMMIT | rollback erases everything, even the attempt row; message unacked → redelivered → processed | `workerCrashBeforeCommitLeavesNoTraceAndTheRedeliveryProcessesIt` |
| after COMMIT, before ack | redelivered → event already PROCESSED → `SKIPPED_ALREADY_PROCESSED`, still 1 fulfilment | `workerCrashAfterCommitBeforeAckIsHarmless` |

**9. Concurrency.** Lock order is the same everywhere (event → payment → order), so two workers can't deadlock.
Reconciliation uses payment → order (a prefix of the same order).

**10. Questions.** *"Why not ack first?"* Ack-then-crash loses the event. *"Why not auto-ack?"* Same thing.
*"Why lock the event row?"* A broker redelivery and a manual replay can race; the second waits, then sees PROCESSED.

---

## Feature 7 — Retries, backoff, jitter, dead letters (Module F)

* **Classification** (`FailureClassifier`): explicit `ProcessingException.Transient/Permanent` first, then known infra
  errors (lock timeouts, DB unreachable) → transient, data errors (constraint violations, malformed payload) →
  permanent, **unknown → transient** (bounded by the retry budget, so no infinite loop).
* **Backoff** (`RetryPolicy`): `exp = min(max, base·2^(n-1))`, delay = `exp/2 + random(0, exp/2)` ("equal jitter").
  Jitter stops 1,000 messages that failed together from retrying together.
* **Scheduling**: a retry is a **new outbox row** with `next_attempt_at = now + delay` — exact per-message delay,
  survives broker restarts, visible in SQL. (RabbitMQ TTL queues suffer head-of-line blocking.)
* **Dead letters**: permanent or budget exhausted → status DEAD + audit row → `basicReject(requeue=false)` → DLQ.
* **Replay** (`DeadLetterService.replay`): requires a reason, only for DEAD events, resets the retry *cycle* (history kept),
  inserts a REPLAY outbox row, audit record in the same transaction. Safe because it just re-runs the normal guarded path.

**Questions.** *"How do you avoid infinite retries?"* Max attempts per cycle + permanent classification + DLQ.
*"What if the failure handler itself fails (DB down)?"* Don't ack; nack with requeue after a 1 s pause — the
message is the only copy of "work to do".

---

## Feature 8 — Stale and out-of-order events (Module I)

Every gateway state change bumps a **version**; webhooks carry it; `payments.gateway_version` stores the newest applied.
`GatewayStateApplier.apply`: `event.version <= applied` → STALE (ignored, recorded as `SKIPPED_STALE`).
Newer version → is the target **reachable** (`PaymentStateMachine.isReachable`, BFS over the transition graph)?
CREATED → CAPTURED is reachable (via AUTHORIZED) so a lost "authorized" webhook doesn't block a valid "captured".
CAPTURED → FAILED is not → permanent failure → human.

**Why versions, not timestamps?** Clocks on different machines disagree; a per-entity counter incremented under a row
lock is a total order for that payment.
**Tested:** `WorkerIT.outOfOrderEventsNeverMovePaymentBackwards`, `shuffledConcurrentEventsConvergeToTheNewestGatewayVersion`
(4 versions × 5 duplicates, shuffled, 20 threads → always REFUNDED v4, one fulfilment).

---

## Feature 9 — Reconciliation (Module G)

* **Inputs:** merchant DB, gateway status API (keyset-paginated `GET /payments?updatedSince=&afterId=`), settlement
  reports (imported idempotently).
* **Rules:** `ReconciliationRules.compare` is a pure function → 12 unit tests, one per rule. 15 mismatch types.
* **Auto-fix allow-list:** `GATEWAY_CAPTURED_MERCHANT_PENDING`, `STATE_LAG` only. Recovery re-locks the payment and
  goes through the same `GatewayStateApplier` as the worker; if a webhook won the race → `ALREADY_CONSISTENT`.
* **Never auto-fixed:** amount mismatch, merchant ahead of gateway, impossible state, double charge, missing settlement.
* **One run at a time:** partial unique index `ON reconciliation_runs ((true)) WHERE status = 'RUNNING'`;
  crashed runs are marked ABANDONED after 30 min.
* **Captured ≠ settled:** separate tables; MISSING_SETTLEMENT = captured before a batch cutoff but not in the batch;
  DELAYED_SETTLEMENT = older than the expected settlement time with no batch yet.

**Questions.** *"Why not just trust the gateway and overwrite?"* Overwriting an ambiguous state can hide fraud or a bug
(e.g. merchant shows CAPTURED but gateway doesn't — maybe a forged event got through). Ambiguous → human.
*"What if recovery and the late webhook happen at the same time?"* Both lock the payment row; second sees a newer
version → STALE. Tested: `ReconciliationIT.recoveryRacingTheLateWebhookStillShipsOnce`.

---

## Feature 10 — Failure injection (Module H)

`FaultInjector` arms one-shot faults **stored in PostgreSQL** (so the API process can arm a fault that fires in the
worker process). Consumed in a `REQUIRES_NEW` transaction (otherwise the crash would roll back the "consumed" mark and
fire forever) — and decided **before** the main transaction opens, because taking a 2nd pooled connection while
holding one can starve the pool under load. The 12 scenarios in `SimulationRunner` drive the real system and record
evidence per step; `SimulationIT` requires all 12 to end PASSED.

---

## Feature 11 — Observability

* JSON logs (Spring Boot structured logging, `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash`) with MDC fields
  `correlationId`, `webhookEventId`, `providerEventId`, `gatewayPaymentId`.
* Correlation id: from `X-Correlation-Id` (validated) or generated → response header → **stored in the outbox payload**
  → restored in the worker's MDC. One id links webhook request → outbox → RabbitMQ → worker logs.
* Micrometer → `/actuator/prometheus`: `payguard_webhooks_total{result}`, `payguard_outbox_backlog`,
  `payguard_outbox_oldest_due_age_seconds`, `payguard_worker_processing_seconds{outcome}`,
  `payguard_worker_failures_total{decision}`, `payguard_reconciliation_mismatches_total{type}`,
  `payguard_reconciliation_open_mismatches`.
* Worker liveness from the DB (recent `processing_attempts` per worker id) — works across processes.

---

## Real bugs CI caught while building this (great interview stories)

| Bug | How it showed up | Lesson |
|---|---|---|
| `'SKIPPED_ALREADY_PROCESSED'` (25 chars) in a `VARCHAR(24)` column | redelivered messages silently failed to record attempts; 300-row SKIP LOCKED test saw 0 attempts | Fixed **forward** in V6 (`ALTER COLUMN`), never by editing an applied migration. Also: errors on "already done" paths must still be logged loudly. |
| Spring's `DataAccessResourceFailureException` is a *subclass* of `NonTransientDataAccessResourceException` | unit test: "DB unreachable" classified PERMANENT | Check type hierarchies; test the classifier directly. |
| Gateway read-timeout surfaced as a plain `RestClientException` | simulation `GATEWAY_API_TIMEOUT` errored instead of returning 503 | Timeouts can surface during body extraction; classify by cause (`IOException`), not by exception class. |
| Tomcat accept queue (100) overflowed on 1,000 simultaneous connections | "header parser received no bytes" in the client | Load generator ≠ system bug; size `accept-count`, and make the client retry transport errors *like a real gateway*, reporting the count. |
| Same-version duplicate in a batch of shuffled events | (anticipated) | Versions alone don't stop two *different* events meaning "paid" — `UNIQUE(order_id)` on fulfilments is the last line. |
| 100 same-key checkouts: one got `503 GATEWAY_UNAVAILABLE` | intermittent CI failure (push run red, PR run of the same commit green) | Not a product bug: the 2 s test read-timeout fired under CPU contention. A concurrency test must assert **correctness** (one order, one charge), not **timing**; it now retries per the API contract. Lesson: one green run doesn't prove a concurrent test is stable. |
| Worker-only process would not start | found in review: security config needs a web app; simulation beans need the simulator | Conditional beans per process role; CI now runs the API and a separate worker. |

---

## Feature — Load test that checks correctness, not just speed

**Problem.** Bugs in idempotency and dedup often appear only under concurrency, and "it's fast" means nothing if
it is fast at shipping orders twice.

**Implementation.** `loadtest/run.mjs` (no dependencies) drives the real split API/worker stack in CI: unique-key
checkouts, a retry storm (6 simultaneous copies per key), 200 capture → fulfilment pipelines with 150 duplicate
redeliveries, and 2,000 signed webhooks with 20 % repeated ids. It exits non-zero only if an invariant breaks.
It sets no latency thresholds, because a threshold invented for a shared CI VM would be a fake benchmark.
The harness was itself tested against a deliberately buggy fake server, and it caught "105 fulfilments for 60 orders".

**Result (CI run 42467595295).** 13/13 invariants held. Webhook ingest p50 28.6-31.8 ms across two runners; checkout p50
805-989 ms. One earlier run was red: the load generator itself had two flaws (non-identical duplicate bodies; treating the
documented 503 "retry with the same key" as a failure instead of retrying). Fixing the *test* made the invariant stricter:
every request must end in 201.

**The finding.** Checkout was ~28× slower than webhook ingest. The difference is consistent with BCrypt: every
Basic-auth request re-verifies a cost-10 BCrypt hash (~78 ms CPU), and a checkout does it twice. See
`testing-results.md` §2 for the arithmetic.

**Interview questions.**
* *Why is BCrypt slow on purpose?* It is designed to make password guessing expensive. That is right for login, and
  wrong for authenticating every API call. Authenticate once, then use tokens or HMAC-checked API keys.
* *Why test the test?* A check that never fails proves nothing. Mutation-style checks (inject the bug, see it fail)
  show the assertion is real.
* *What is p99 and why report it?* 99 % of requests were faster than this. Averages hide the slow tail, and the
  tail is what users and retry storms feel.
* *What is retry amplification?* 300 logical requests became ~1,327 attempts in the storm. Every retry adds load to an
  already overloaded system. Backoff with jitter, `Retry-After`, attempt caps and cheap rejection (409 before expensive
  work) keep it bounded.
* *How would you lower end-to-end latency?* The 500 ms outbox poll dominates. Poll more often (more DB load), or wake
  the publisher with PostgreSQL `LISTEN/NOTIFY` after commit and keep polling as the safety net.
