-- =====================================================================
-- V4: API request idempotency (merchant side).
--
-- A client sends "Idempotency-Key: abc" with POST /api/checkout.
-- If the network drops the response and the client retries with the same
-- key, we must return the SAME result instead of creating a second order.
--
-- Columns explained:
--   request_fingerprint : SHA-256 of the request. Same key + different body
--                         is a client bug -> 422, never silently reuse.
--   status              : IN_PROGRESS while a request owns the key.
--   locked_until        : lease. If the server crashes mid-request, the key
--                         is not stuck forever; after the lease expires a
--                         retry may take over.
--   lease_token         : fencing token. Rotated on every takeover; all
--                         writes check it, so a slow "zombie" request that
--                         lost its lease cannot overwrite the new owner.
--   recovery_point      : how far a crashed request got (STARTED ->
--                         ORDER_CREATED -> COMPLETED), so a retry RESUMES
--                         instead of redoing completed steps.
-- =====================================================================

CREATE TABLE idempotency_records (
    id                   BIGSERIAL     PRIMARY KEY,
    scope                VARCHAR(64)   NOT NULL,        -- e.g. 'POST /api/checkout'
    idempotency_key      VARCHAR(255)  NOT NULL,
    request_fingerprint  VARCHAR(64)   NOT NULL,
    status               VARCHAR(16)   NOT NULL,
    recovery_point       VARCHAR(32)   NOT NULL DEFAULT 'STARTED',
    resource_id          VARCHAR(64),
    lease_token          UUID,
    locked_until         TIMESTAMPTZ,
    response_status      INT,
    response_body        TEXT,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ   NOT NULL,

    CONSTRAINT uq_idempotency_scope_key UNIQUE (scope, idempotency_key),
    CONSTRAINT ck_idempotency_status    CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    CONSTRAINT ck_idempotency_completed CHECK (status <> 'COMPLETED'
                                               OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);

-- Used by the cleanup job: DELETE ... WHERE expires_at < now()
CREATE INDEX ix_idempotency_expires_at ON idempotency_records (expires_at);
