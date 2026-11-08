-- =====================================================================
-- V3: Webhook ingestion.
-- =====================================================================

-- ---------------------------------------------------------------------
-- webhook_events: every AUTHENTIC (signature-verified) webhook we accepted.
-- The UNIQUE (provider, provider_event_id) constraint is the deduplication
-- guarantee. It holds even with 1,000 concurrent deliveries of the same
-- event, because the database - not application code - enforces it.
-- ---------------------------------------------------------------------
CREATE TABLE webhook_events (
    id                       BIGSERIAL     PRIMARY KEY,
    provider                 VARCHAR(32)   NOT NULL,
    provider_event_id        VARCHAR(128)  NOT NULL,
    event_type               VARCHAR(64)   NOT NULL,
    gateway_payment_id       VARCHAR(64),               -- NULL only for IGNORED (unknown type) events
    gateway_payment_version  BIGINT,                    -- used in Phase 3 for stale-event detection
    provider_created_at      TIMESTAMPTZ,
    raw_body                 TEXT          NOT NULL,    -- exact bytes we verified (audit / re-verification)
    payload                  JSONB         NOT NULL,    -- same content, queryable
    payload_sha256           VARCHAR(64)   NOT NULL,
    processing_status        VARCHAR(16)   NOT NULL DEFAULT 'RECEIVED',
    duplicate_count          INT           NOT NULL DEFAULT 0,
    last_duplicate_at        TIMESTAMPTZ,
    received_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at             TIMESTAMPTZ,

    CONSTRAINT uq_webhook_events_provider_event UNIQUE (provider, provider_event_id),
    CONSTRAINT ck_webhook_events_status CHECK (processing_status IN
        ('RECEIVED','PROCESSING','PROCESSED','IGNORED','FAILED','DEAD')),
    CONSTRAINT ck_webhook_events_payment_ref CHECK
        (processing_status = 'IGNORED' OR gateway_payment_id IS NOT NULL),
    CONSTRAINT ck_webhook_events_dup_count CHECK (duplicate_count >= 0)
);

CREATE INDEX ix_webhook_events_gateway_payment ON webhook_events (gateway_payment_id);
CREATE INDEX ix_webhook_events_received_at     ON webhook_events (received_at DESC);

-- ---------------------------------------------------------------------
-- webhook_rejections: requests that FAILED verification.
--
-- Why a separate table instead of a "verified=false" column on
-- webhook_events? Security. If unverified requests were inserted into
-- webhook_events, an attacker could send a forged request with id
-- "evt_123" BEFORE the real gateway does. The unique constraint would then
-- make us treat the REAL event as a "duplicate" and drop it. Unverified
-- data must never occupy the dedup key space.
-- ---------------------------------------------------------------------
CREATE TABLE webhook_rejections (
    id                BIGSERIAL     PRIMARY KEY,
    provider          VARCHAR(32)   NOT NULL,
    claimed_event_id  VARCHAR(128),                -- untrusted, informational only
    reason            VARCHAR(32)   NOT NULL,
    detail            VARCHAR(500),
    body_sha256       VARCHAR(64),
    body_excerpt      VARCHAR(1000),               -- truncated, untrusted
    source_ip         VARCHAR(64),
    received_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_webhook_rejections_reason CHECK (reason IN
        ('MISSING_SIGNATURE','MALFORMED_SIGNATURE','INVALID_SIGNATURE',
         'TIMESTAMP_OUT_OF_TOLERANCE','MALFORMED_PAYLOAD'))
);

CREATE INDEX ix_webhook_rejections_received_at ON webhook_rejections (received_at);
