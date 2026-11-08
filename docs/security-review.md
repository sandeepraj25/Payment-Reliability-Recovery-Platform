# Security Review (Phase 5)

Scope: the PayGuard backend, dashboard and local deployment. This is a self-review, not a penetration
test. Each item says what is implemented, where, and what is knowingly left out.

| Area | Status | Implementation |
|------|--------|----------------|
| Webhook authenticity | ✅ | HMAC-SHA256 over the **raw bytes** (`WebhookController` binds `byte[]`), `MessageDigest.isEqual` constant-time compare, signed timestamp ±5 min (replay window), several secrets for rotation (`WebhookSignature`, `PayGuardSimVerifier`). |
| Forged-event squatting | ✅ | Failed verifications go to `webhook_rejections`, never `webhook_events`, so they cannot occupy a real event id. Tested (`WebhookIngestionIT.forgedEventCannotSquatOnARealEventId`). |
| Replay protection | ✅ | Timestamp tolerance + event-id dedup (inside the window a replay is just a duplicate). |
| Authentication | ✅ | HTTP Basic over a stateless API, BCrypt-hashed in-memory users (`SecurityConfig`). Credentials only from env vars; app refuses to start with missing/short (<12) passwords (`SecurityProperties`). |
| Authorization | ✅ | `ADMIN` (changes: replay, resolve, simulations, checkout), `VIEWER` (read-only GETs), `GATEWAY_CLIENT` (simulator API only). Everything else `denyAll`. Tested (`OpsIT.viewerCanReadButNotChangeAnything`). |
| Dangerous demo controls | ✅ | Fault injection and the simulation lab only **exist** when `PAYGUARD_FAULTS_ENABLED=true` (bean conditions) - in production the URLs are 404, not merely protected. Simulator also off by default. |
| Input validation | ✅ | Bean Validation on DTOs, header size limits, strict webhook schema parser (integers only for money, UTF-8 strict, id patterns), 64 KiB body limit, Jackson `accept-float-as-int=false`. |
| Money correctness | ✅ | `BIGINT` minor units everywhere; fees use integer arithmetic; fractional amounts rejected, never rounded. |
| Rate limiting | ✅ | Token bucket per client IP (`RateLimitFilter`): webhooks 200/s, API 50/s, burst 2x, 429 + `Retry-After`. Idle-bucket eviction + map cap (memory DoS). Per-instance (documented; Redis would make it global). |
| CORS | ✅ | Explicit origin list (`PAYGUARD_CORS_ORIGINS`), never `*`. In docker-compose nginx serves UI and API from one origin, so CORS is not even needed. |
| CSRF | ✅ n/a | Stateless, header-authenticated API; no ambient cookies to ride on. |
| Secrets in logs | ✅ | Signature headers, bodies and secrets are never logged; rejection logs carry only reason/claimed id/IP. Correlation ids validated (`^[A-Za-z0-9._-]{8,64}$`) to prevent log injection. |
| Safe error handling | ✅ | `GlobalExceptionHandler`: stable error codes, no stack traces or exception messages leaked (`server.error.include-*: never`), framework 4xx keep their status, DB outages → 503. |
| Audit | ✅ | `audit_logs` append-only, **enforced by a DB trigger** (UPDATE/DELETE raise). Replays and manual resolutions require a reason. |
| Security headers | ✅ | API: CSP `default-src 'none'`, Spring Security defaults (nosniff, frame deny, no-cache). nginx: nosniff, `X-Frame-Options DENY`, `Referrer-Policy no-referrer`. |
| Dashboard credential storage | ⚠️ accepted | Basic credentials kept in `sessionStorage` for the tab's lifetime (not localStorage). Acceptable for a local ops tool; production would use OIDC + short-lived tokens. |
| CSV export injection | ✅ | Cells starting with `= + - @` are prefixed (`ReconciliationController.q`). |
| Container | ✅ | Backend runs as non-root (uid 10001) on a JRE-only image; ports bound to 127.0.0.1 in compose. |
| TLS | ⚠️ out of scope | Local stack is plain HTTP. In production terminate TLS at the load balancer; webhooks must be HTTPS. |
| Dependency scanning | ⚠️ not done | Add Dependabot / OWASP dependency-check to CI before any real use. |
| DB least privilege | ⚠️ documented | One DB role is used. Production: separate migration role; app role with `INSERT, SELECT` only on `audit_logs`. |

**Do not claim production readiness**: this review plus passing tests is evidence of careful design, not a
certification.
