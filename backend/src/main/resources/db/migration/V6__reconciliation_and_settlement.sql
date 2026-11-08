-- =====================================================================
-- V6: Phase 3 - settlement and reconciliation.
--
--   CAPTURED  = the customer's card was charged (gateway knows it immediately)
--   SETTLED   = the money actually reached the merchant's bank, later, in a batch, minus fees
-- They are different facts with different timelines, so they live in different tables.
-- =====================================================================

-- When did the gateway capture? Needed to decide whether a settlement is late or missing.
ALTER TABLE gateway.gateway_payments ADD COLUMN captured_at TIMESTAMPTZ;
CREATE INDEX ix_gateway_payments_updated ON gateway.gateway_payments (updated_at, id);

-- ---------------------------------------------------------------------
-- Gateway side: what the gateway has paid out. A payment is settled at most once.
-- ---------------------------------------------------------------------
CREATE TABLE gateway.settlement_batches (
    id            VARCHAR(64)  PRIMARY KEY,
    cutoff        TIMESTAMPTZ  NOT NULL,          -- every payment captured before this SHOULD be in the batch
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE gateway.settlement_items (
    batch_id      VARCHAR(64)  NOT NULL REFERENCES gateway.settlement_batches (id),
    payment_id    VARCHAR(64)  NOT NULL REFERENCES gateway.gateway_payments (id),
    gross_minor   BIGINT       NOT NULL,
    fee_minor     BIGINT       NOT NULL,
    net_minor     BIGINT       NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    PRIMARY KEY (batch_id, payment_id),
    CONSTRAINT uq_gateway_settlement_payment UNIQUE (payment_id),
    CONSTRAINT ck_gateway_settlement_math CHECK (gross_minor > 0 AND fee_minor >= 0 AND net_minor = gross_minor - fee_minor)
);

-- ---------------------------------------------------------------------
-- Merchant side: settlement reports as IMPORTED from the gateway (the merchant's copy).
-- Import is idempotent: re-importing a report changes nothing.
-- ---------------------------------------------------------------------
CREATE TABLE settlement_batches (
    batch_id      VARCHAR(64)  PRIMARY KEY,
    cutoff        TIMESTAMPTZ  NOT NULL,
    item_count    INT          NOT NULL,
    imported_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE settlement_records (
    id                  BIGSERIAL    PRIMARY KEY,
    batch_id            VARCHAR(64)  NOT NULL REFERENCES settlement_batches (batch_id),
    gateway_payment_id  VARCHAR(64)  NOT NULL,
    gross_minor         BIGINT       NOT NULL,
    fee_minor           BIGINT       NOT NULL,
    net_minor           BIGINT       NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    imported_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_settlement_records UNIQUE (batch_id, gateway_payment_id),
    CONSTRAINT ck_settlement_math CHECK (net_minor = gross_minor - fee_minor AND fee_minor >= 0)
);
CREATE INDEX ix_settlement_records_payment ON settlement_records (gateway_payment_id);

-- ---------------------------------------------------------------------
-- Reconciliation runs. At most ONE run may be RUNNING (partial unique index),
-- so two schedulers / two operators can never reconcile - and recover - at the same time.
-- ---------------------------------------------------------------------
CREATE TABLE reconciliation_runs (
    id                 BIGSERIAL    PRIMARY KEY,
    trigger_type       VARCHAR(16)  NOT NULL,           -- SCHEDULED | MANUAL
    triggered_by       VARCHAR(64)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,           -- RUNNING | COMPLETED | FAILED | ABANDONED
    window_from        TIMESTAMPTZ  NOT NULL,
    window_to          TIMESTAMPTZ  NOT NULL,
    merchant_payments  INT          NOT NULL DEFAULT 0,
    gateway_payments   INT          NOT NULL DEFAULT 0,
    mismatches_found   INT          NOT NULL DEFAULT 0,
    auto_resolved      INT          NOT NULL DEFAULT 0,
    manual_review      INT          NOT NULL DEFAULT 0,
    error              VARCHAR(1000),
    started_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    CONSTRAINT ck_recon_status  CHECK (status IN ('RUNNING','COMPLETED','FAILED','ABANDONED')),
    CONSTRAINT ck_recon_trigger CHECK (trigger_type IN ('SCHEDULED','MANUAL'))
);
CREATE UNIQUE INDEX uq_recon_one_running ON reconciliation_runs ((true)) WHERE status = 'RUNNING';

CREATE TABLE reconciliation_mismatches (
    id                  BIGSERIAL     PRIMARY KEY,
    run_id              BIGINT        NOT NULL REFERENCES reconciliation_runs (id),
    mismatch_type       VARCHAR(48)   NOT NULL,
    severity            VARCHAR(16)   NOT NULL,          -- INFO | WARNING | CRITICAL
    gateway_payment_id  VARCHAR(64),
    merchant_payment_id UUID,
    order_id            UUID,
    merchant_value      VARCHAR(200),
    gateway_value       VARCHAR(200),
    evidence            JSONB         NOT NULL,
    resolution          VARCHAR(24)   NOT NULL,          -- see check below
    resolution_note     VARCHAR(500),
    resolved_by         VARCHAR(64),
    resolved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_mismatch_resolution CHECK (resolution IN
        ('OPEN','AUTO_RESOLVED','ALREADY_CONSISTENT','INFORMATIONAL','RESOLVED_MANUALLY')),
    CONSTRAINT ck_mismatch_severity CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    CONSTRAINT uq_mismatch_per_run UNIQUE NULLS NOT DISTINCT (run_id, mismatch_type, gateway_payment_id, merchant_payment_id)
);
CREATE INDEX ix_mismatch_open ON reconciliation_mismatches (created_at) WHERE resolution = 'OPEN';
CREATE INDEX ix_mismatch_run  ON reconciliation_mismatches (run_id);

-- ---------------------------------------------------------------------
-- Fix found by CI (Phase 2): 'SKIPPED_ALREADY_PROCESSED' is 25 characters but V5 declared
-- outcome VARCHAR(24), so every redelivery of an already-processed message failed to record
-- its attempt. Fixed FORWARD in a new migration - never edit a migration that may have run
-- somewhere (Flyway checksums would reject it, and other databases would silently differ).
-- ---------------------------------------------------------------------
ALTER TABLE processing_attempts ALTER COLUMN outcome TYPE VARCHAR(32);
