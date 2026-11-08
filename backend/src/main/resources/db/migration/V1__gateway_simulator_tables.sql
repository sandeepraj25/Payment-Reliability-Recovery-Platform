-- =====================================================================
-- V1: Gateway simulator tables.
--
-- The simulated payment gateway lives in its own schema ("gateway") to make
-- the boundary obvious: in real life this data belongs to a different company
-- (Razorpay, Stripe, ...). The merchant code never reads these tables
-- directly - it only talks to the gateway over HTTP and receives webhooks.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS gateway;

-- ---------------------------------------------------------------------
-- gateway_payments: the gateway's AUTHORITATIVE view of each payment.
-- Reconciliation (Phase 3) compares the merchant DB against this table.
-- ---------------------------------------------------------------------
CREATE TABLE gateway.gateway_payments (
    id                          VARCHAR(40)  PRIMARY KEY,          -- e.g. pay_7Hq2...
    gateway_reference           VARCHAR(40)  NOT NULL,             -- simulated bank/UTR style reference
    merchant_order_ref          VARCHAR(64)  NOT NULL,             -- the merchant's order id
    amount_minor                BIGINT       NOT NULL,             -- paise; NEVER floating point
    amount_refunded_minor       BIGINT       NOT NULL DEFAULT 0,
    currency                    VARCHAR(3)   NOT NULL,
    status                      VARCHAR(32)  NOT NULL,
    failure_reason              VARCHAR(255),
    -- API idempotency for "create payment": same key => same payment, never two.
    create_idempotency_key      VARCHAR(255) NOT NULL,
    create_request_fingerprint  VARCHAR(64)  NOT NULL,
    version                     BIGINT       NOT NULL DEFAULT 0,   -- bumped on every state change
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_gw_payments_reference      UNIQUE (gateway_reference),
    CONSTRAINT uq_gw_payments_create_key     UNIQUE (create_idempotency_key),
    CONSTRAINT ck_gw_payments_amount         CHECK (amount_minor > 0),
    CONSTRAINT ck_gw_payments_refunded       CHECK (amount_refunded_minor BETWEEN 0 AND amount_minor),
    CONSTRAINT ck_gw_payments_currency       CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_gw_payments_status         CHECK (status IN ('CREATED','AUTHORIZED','CAPTURED','FAILED',
                                                               'REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED')),
    CONSTRAINT ck_gw_payments_version        CHECK (version >= 0)
);

CREATE INDEX ix_gw_payments_merchant_order_ref ON gateway.gateway_payments (merchant_order_ref);

-- ---------------------------------------------------------------------
-- gateway_refunds: each refund is a separate financial effect, so each
-- refund request needs its own idempotency key. Without this, a retried
-- "refund Rs.500" HTTP call could refund Rs.1000.
-- ---------------------------------------------------------------------
CREATE TABLE gateway.gateway_refunds (
    id                    VARCHAR(40)  PRIMARY KEY,
    payment_id            VARCHAR(40)  NOT NULL REFERENCES gateway.gateway_payments (id),
    amount_minor          BIGINT       NOT NULL,
    idempotency_key       VARCHAR(255) NOT NULL,
    request_fingerprint   VARCHAR(64)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Leading column payment_id also serves "refunds of payment X" lookups.
    CONSTRAINT uq_gw_refunds_payment_key UNIQUE (payment_id, idempotency_key),
    CONSTRAINT ck_gw_refunds_amount      CHECK (amount_minor > 0)
);

-- ---------------------------------------------------------------------
-- gateway_events: every state change produces exactly one event, written
-- in the SAME transaction as the state change (the gateway's own outbox).
-- payment_version lets the merchant detect stale/out-of-order events.
-- ---------------------------------------------------------------------
CREATE TABLE gateway.gateway_events (
    id               VARCHAR(40)  PRIMARY KEY,                -- e.g. evt_...
    payment_id       VARCHAR(40)  NOT NULL REFERENCES gateway.gateway_payments (id),
    event_type       VARCHAR(64)  NOT NULL,
    payment_version  BIGINT       NOT NULL,
    payload          JSONB        NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_gw_events_payment_version UNIQUE (payment_id, payment_version),
    CONSTRAINT ck_gw_events_type CHECK (event_type IN ('payment.authorized','payment.captured',
                                                       'payment.failed','payment.refunded'))
);

-- ---------------------------------------------------------------------
-- webhook_deliveries: log of every HTTP attempt the gateway made to the
-- merchant. Evidence for "did the gateway actually send it?" questions.
-- ---------------------------------------------------------------------
CREATE TABLE gateway.webhook_deliveries (
    id            BIGSERIAL    PRIMARY KEY,
    event_id      VARCHAR(40)  NOT NULL REFERENCES gateway.gateway_events (id),
    trigger_type  VARCHAR(20)  NOT NULL,
    http_status   INT,
    error         VARCHAR(500),
    duration_ms   INT,
    attempted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_gw_deliveries_trigger CHECK (trigger_type IN ('AUTO','MANUAL_REDELIVERY'))
);

CREATE INDEX ix_gw_deliveries_event ON gateway.webhook_deliveries (event_id, attempted_at);
