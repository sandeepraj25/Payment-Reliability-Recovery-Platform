# Demo Walkthrough (and 5-minute video script)

## Start
```bash
cp .env.example .env                       # set every password (>= 12 chars) and PAYGUARD_FAULTS_ENABLED=true
docker compose up --build                  # postgres, rabbitmq, backend (API), 2 workers, dashboard
open http://localhost:5173                 # sign in with PAYGUARD_ADMIN_USER / PAYGUARD_ADMIN_PASSWORD
```
RabbitMQ UI: http://localhost:15672 (PAYGUARD_RABBIT_USER / PASSWORD).

## Video script (≈5 minutes)

| Time | Show | Say |
|------|------|-----|
| 0:00 | Architecture page, click each box | "A customer pays ₹4,999, the gateway captures, the webhook is lost or the server crashes — the customer paid but we never ship. PayGuard is the merchant backend that prevents, detects and recovers from that. Gateway → signed webhook → PostgreSQL + outbox in one transaction → RabbitMQ → worker → reconciliation." |
| 0:45 | Failure lab → **Missing webhook** → Execute | "This runs real backend code. The gateway captures but drops the webhook." Point at step 3: "order still PAYMENT_PENDING — nothing would ever fix this by itself." Step 4: "reconciliation compares with the gateway's status API and applies the newer state through the same guarded code the worker uses." Invariants: "exactly one fulfilment, merchant matches gateway." |
| 1:45 | Failure lab → **Worker crash after commit, before ack** | "Commit first, ack second. The redelivered message finds the event PROCESSED and is skipped — see SKIPPED_ALREADY_PROCESSED (redelivered)." |
| 2:30 | Failure lab → **Out-of-order events** | "Version 2 arrives before version 1. v1 is stale and ignored — the payment never moves backwards." |
| 3:00 | Webhook events → select an event | "Retry history with backoff delays, outbox rows, exact signed payload and hash." |
| 3:30 | Payments → select a payment | "Gateway vs merchant side by side, plus the full lifecycle timeline and audit trail." |
| 4:00 | Reconciliation → Start → open a mismatch → Export CSV | "Only provably safe cases are auto-fixed; amount mismatches or a merchant claiming a capture the gateway never made wait for a human, with evidence." |
| 4:30 | Interview demo → run a scenario, page through the 6 questions | "Every answer here is the recorded backend run — nothing is scripted in the UI." |

## Talking points to have ready
* Why three kinds of idempotency (request / event / fulfilment) — see interview-guide.
* What exactly-once means here (at-least-once + idempotent effects).
* The bugs CI caught (interview-guide, last section) — shows you test what you claim.
* What is NOT production-ready (security-review.md, PROJECT_PROGRESS.md "Known limitations").
