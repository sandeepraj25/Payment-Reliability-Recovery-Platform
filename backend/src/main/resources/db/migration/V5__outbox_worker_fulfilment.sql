-- =====================================================================
-- V5: Phase 2 - reliable asynchronous processing.
--   outbox_events        transactional outbox (also the retry scheduler)
--   processing_attempts  every worker attempt, success or failure (retry history)
--   fulfilments          the idempotent business effect: one per order, ever
--   audit_logs           append-only record of operator / recovery actions
--   fault_injections     demo-only fault switches, shared by API and worker processes
-- =====================================================================

-- ---------------------------------------------------------------------
-- webhook_events gains processing bookkeeping.
--   RETRY_SCHEDULED : a transient failure happened; a delayed outbox row will retry it
--   DEAD            : gave up (permanent failure or retries exhausted); needs a human
-- ---------------------------------------------------------------------
ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_status;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_status CHECK (processing_status IN
    ('RECEIVED','RETRY_SCHEDULED','PROCESSED','IGNORED','DEAD'));

ALTER TABLE webhook_events
    ADD COLUMN attempt_count       INT          NOT NULL DEFAULT 0,  -- all attempts ever
    ADD COLUMN cycle_attempts      INT          NOT NULL DEFAULT 0,  -- attempts since the last manual replay
    ADD COLUMN processing_result   VARCHAR(32),                     -- APPLIED | NO_CHANGE | STALE_IGNORED | ...
    ADD COLUMN last_error          VARCHAR(1000),
    ADD CONSTRAINT ck_webhook_events_attempts CHECK (attempt_count >= 0 AND cycle_attempts >= 0);

CREATE INDEX ix_webhook_events_status ON webhook_events (processing_status, received_at);

-- ---------------------------------------------------------------------
-- payments remember the newest gateway version applied. An event whose
-- version is <= this value is stale (delayed / out of order) and is ignored.
-- ---------------------------------------------------------------------
ALTER TABLE payments
    ADD COLUMN gateway_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_payments_gateway_version CHECK (gateway_version >= 0);

-- ---------------------------------------------------------------------
-- outbox_events
-- Written in the SAME transaction as the thing it announces, so "event stored"
-- and "event will be published" can never disagree. A row may be published more
-- than once (crash between broker confirm and DB update) -> consumers dedup.
--
-- Not UNIQUE(webhook_event_id): retries and manual replays add rows on purpose.
-- ---------------------------------------------------------------------
CREATE TABLE outbox_events (
    id                BIGSERIAL     PRIMARY KEY,
    webhook_event_id  BIGINT        NOT NULL REFERENCES webhook_events (id),
    purpose           VARCHAR(16)   NOT NULL,           -- INGEST | RETRY | REPLAY | RECOVERY
    routing_key       VARCHAR(64)   NOT NULL,
    payload           JSONB         NOT NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    publish_attempts  INT           NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_error        VARCHAR(500),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,

    CONSTRAINT ck_outbox_status  CHECK (status IN ('PENDING','PUBLISHED')),
    CONSTRAINT ck_outbox_purpose CHECK (purpose IN ('INGEST','RETRY','REPLAY','RECOVERY')),
    CONSTRAINT ck_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

-- Partial index: the publisher only ever looks for due PENDING rows, and PUBLISHED rows
-- (the vast majority over time) are not in this index at all, so it stays tiny.
CREATE INDEX ix_outbox_due ON outbox_events (next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_webhook_event ON outbox_events (webhook_event_id);

-- ---------------------------------------------------------------------
-- processing_attempts: one row per worker attempt.
-- attempt_no is assigned while holding the webhook_events row lock, so it is gap-free per event.
-- ---------------------------------------------------------------------
CREATE TABLE processing_attempts (
    id                BIGSERIAL     PRIMARY KEY,
    webhook_event_id  BIGINT        NOT NULL REFERENCES webhook_events (id),
    attempt_no        INT           NOT NULL,
    worker_id         VARCHAR(64)   NOT NULL,
    redelivered       BOOLEAN       NOT NULL DEFAULT FALSE,     -- RabbitMQ's redelivered flag
    outcome           VARCHAR(24)   NOT NULL,
    error_class       VARCHAR(200),
    error_message     VARCHAR(1000),
    retry_delay_ms    BIGINT,                                   -- set when a retry was scheduled
    started_at        TIMESTAMPTZ   NOT NULL,
    finished_at       TIMESTAMPTZ   NOT NULL,
    duration_ms       INT           NOT NULL,

    CONSTRAINT uq_processing_attempts UNIQUE (webhook_event_id, attempt_no),
    CONSTRAINT ck_processing_outcome CHECK (outcome IN
        ('SUCCESS','SKIPPED_STALE','SKIPPED_ALREADY_PROCESSED','TRANSIENT_FAILURE','PERMANENT_FAILURE'))
);

-- ---------------------------------------------------------------------
-- fulfilments: the "ship the order" side effect.
-- UNIQUE(order_id) is the last line of defence: even if two DIFFERENT events
-- (or a webhook AND a reconciliation recovery) both say "paid", only one row
-- can ever exist, so the order ships once.
-- ---------------------------------------------------------------------
CREATE TABLE fulfilments (
    id              BIGSERIAL     PRIMARY KEY,
    order_id        UUID          NOT NULL REFERENCES orders (id),
    payment_id      UUID          NOT NULL REFERENCES payments (id),
    trigger_source  VARCHAR(32)   NOT NULL,
    trigger_ref     VARCHAR(128),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_fulfilments_order UNIQUE (order_id),
    CONSTRAINT ck_fulfilments_source CHECK (trigger_source IN ('WEBHOOK','RECONCILIATION','MANUAL'))
);

-- ---------------------------------------------------------------------
-- audit_logs: append-only. Who did what, why, before -> after.
-- In production the app's DB role would get INSERT/SELECT only (see docs).
-- ---------------------------------------------------------------------
CREATE TABLE audit_logs (
    id              BIGSERIAL     PRIMARY KEY,
    actor           VARCHAR(64)   NOT NULL,       -- operator username or 'system:<component>'
    action          VARCHAR(64)   NOT NULL,
    target_type     VARCHAR(32)   NOT NULL,
    target_id       VARCHAR(128)  NOT NULL,
    reason          VARCHAR(500),
    before_state    JSONB,
    after_state     JSONB,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_target  ON audit_logs (target_type, target_id);
CREATE INDEX ix_audit_created ON audit_logs (created_at DESC);

-- Append-only, enforced by the database itself (not just by convention).
CREATE FUNCTION audit_logs_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only';
END $$;
CREATE TRIGGER trg_audit_logs_immutable BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_immutable();

-- ---------------------------------------------------------------------
-- fault_injections: DEMO ONLY. Armed through the fault API, which only exists
-- when payguard.faults.enabled=true. Stored in the DB (not in memory) so that the
-- API process can arm a fault that fires inside the separate worker process.
-- ---------------------------------------------------------------------
CREATE TABLE fault_injections (
    fault_point     VARCHAR(48)   PRIMARY KEY,
    remaining       INT           NOT NULL,
    armed_by        VARCHAR(64)   NOT NULL,
    armed_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_fault_remaining CHECK (remaining >= 0)
);
