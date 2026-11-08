# Learning Roadmap (beginner-friendly)

Study in this order. For each topic: what it is in plain words → where PayGuard uses it → what to be able to explain.

1. **Java OOP** — classes bundle data + behaviour; encapsulation hides internals. *PayGuard*: `GatewayPayment.moveTo`
   is package-private so only the service (after the state machine says yes) can change status. *Explain*: why private setters protect invariants.
2. **Collections** — `Map`, `Set`, `List`; `EnumMap`/`EnumSet` are fast maps/sets keyed by enums. *PayGuard*:
   `PaymentStateMachine.ALLOWED` is an `EnumMap<PaymentStatus, EnumSet<PaymentStatus>>`. *Explain*: O(1) lookup, why a table beats if/else.
3. **Multithreading** — many threads run at once; shared data needs coordination. *PayGuard*: tests fire 1,000
   requests with `CompletableFuture`; `CountDownLatch` releases threads together. *Explain*: race condition vs deadlock; thread pool.
4. **Spring Boot** — framework that wires objects (dependency injection), runs a web server, manages transactions.
   *PayGuard*: constructor injection everywhere, `@Transactional`, `TransactionTemplate`, `@ConfigurationProperties`. *Explain*: what a proxy does for `@Transactional` (and why self-calls bypass it).
5. **REST APIs** — resources + HTTP verbs. POST creates, GET reads. *PayGuard*: `POST /api/checkout`, `GET /api/orders/{id}`.
   *Explain*: why POST is not idempotent by default and how `Idempotency-Key` fixes that.
6. **HTTP** — status codes: 2xx success, 4xx client error (don't retry), 5xx server error (retry). *PayGuard*: webhook
   returns 200 for duplicates, 401 for bad signatures, 5xx only when storage failed. *Explain*: why the status code is a contract with the gateway's retry logic.
7. **DBMS** — software that stores data safely and lets many users use it at once. *PayGuard*: PostgreSQL is the source of truth.
8. **SQL** — `SELECT/INSERT/UPDATE/DELETE`, `JOIN`, constraints. *PayGuard*: `INSERT … ON CONFLICT DO NOTHING RETURNING id`.
   *Explain*: upsert; `RETURNING`.
9. **ACID** — Atomic (all or nothing), Consistent (constraints hold), Isolated (concurrent TXs don't see half-done work),
   Durable (committed = survives a crash). *PayGuard*: order + recovery point commit atomically; webhook 200 only after durable commit.
10. **Indexing** — like a book's index: find rows without scanning everything. Unique index = also forbids duplicates.
    *PayGuard*: `uq_webhook_events_provider_event`, `ix_idempotency_expires_at`. *Explain*: B-tree, cost of indexes on writes, partial indexes (outbox, Phase 2).
11. **Transactions & isolation** — READ COMMITTED (PostgreSQL default): each statement sees data committed before it started.
    *PayGuard*: why the duplicate `UPDATE` after a conflicting `INSERT` sees the winner's row. *Explain*: dirty/non-repeatable/phantom reads; `SELECT … FOR UPDATE`.
12. **Race conditions** — result depends on timing. Classic: check-then-act ("if not exists, insert").
    *PayGuard*: replaced by atomic SQL + unique constraints; tested with 100/1,000 concurrent requests.
13. **Idempotency** — doing it twice has the same effect as once. Real life: pressing a lift button again doesn't call two lifts.
    *PayGuard*: three layers (request, event, fulfilment) — see interview guide.
14. **Message queues** — a post box between services: producer drops messages, consumer picks them up later. Survives
    the consumer being down. *PayGuard*: RabbitMQ in Phase 2 with acks, retries, dead-letter queue.
15. **Distributed systems basics** — networks drop, delay and duplicate messages; machines crash; there is no global clock.
    *PayGuard*: every cross-system call (gateway HTTP, webhook) is assumed to fail in any of these ways.
    *Explain*: at-least-once vs at-most-once vs "exactly-once effect"; the two generals problem in one sentence.
16. **Eventual consistency** — systems may disagree for a while but converge. *PayGuard*: gateway says CAPTURED before
    the merchant does; webhooks + reconciliation make them converge. *Explain*: why "eventually" needs a mechanism (reconciliation), not hope.
17. **System design trade-offs** — every choice costs something. *PayGuard*: monolith vs microservices (ADR-002),
    PostgreSQL vs Redis for dedup (ADR-003), pessimistic vs optimistic locking. *Explain*: name the cost of your own choice before the interviewer does.

Suggested practice: after each phase, re-implement one small piece yourself without looking (e.g. the
`INSERT … ON CONFLICT` dedup, or the state machine table) and run the tests against your version.
