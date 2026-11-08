-- =====================================================================
-- V2: Merchant-side orders and payments (schema "public").
-- This is the merchant's OWN view of the world. It can be wrong or stale
-- (e.g. a webhook was lost) - that is exactly what PayGuard detects.
-- =====================================================================

CREATE TABLE orders (
    id             UUID         PRIMARY KEY,
    customer_ref   VARCHAR(64)  NOT NULL,
    amount_minor   BIGINT       NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    fulfilled_at   TIMESTAMPTZ,
    version        BIGINT       NOT NULL DEFAULT 0,      -- JPA optimistic locking
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_orders_amount    CHECK (amount_minor > 0),
    CONSTRAINT ck_orders_currency  CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_orders_status    CHECK (status IN ('PAYMENT_PENDING','PAID','FULFILLED',
                                                     'PAYMENT_FAILED','CANCELLED')),
    -- An order is FULFILLED if and only if fulfilled_at is set.
    CONSTRAINT ck_orders_fulfilled CHECK ((status = 'FULFILLED') = (fulfilled_at IS NOT NULL))
);

CREATE INDEX ix_orders_status_created ON orders (status, created_at);

-- ---------------------------------------------------------------------
-- payments: one row per payment ATTEMPT for an order.
-- An order can have several attempts (customer retries after a failure).
--
-- NOTE: we deliberately do NOT add "at most one CAPTURED payment per order"
-- as a unique constraint. Customers really can be charged twice (two tabs,
-- two attempts). The merchant DB must be able to RECORD that reality so
-- reconciliation can detect it and trigger a refund. We protect against
-- double FULFILMENT instead (Phase 2), not against recording a double charge.
-- ---------------------------------------------------------------------
CREATE TABLE payments (
    id                     UUID         PRIMARY KEY,
    order_id               UUID         NOT NULL REFERENCES orders (id),
    provider               VARCHAR(32)  NOT NULL,
    gateway_payment_id     VARCHAR(64),                 -- NULL until the gateway confirms creation
    amount_minor           BIGINT       NOT NULL,
    amount_refunded_minor  BIGINT       NOT NULL DEFAULT 0,
    currency               VARCHAR(3)   NOT NULL,
    status                 VARCHAR(32)  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- PostgreSQL treats NULLs as distinct, so many "not yet created at
    -- gateway" rows are allowed, but a real gateway id maps to one row only.
    CONSTRAINT uq_payments_provider_gateway_id UNIQUE (provider, gateway_payment_id),
    CONSTRAINT ck_payments_amount    CHECK (amount_minor > 0),
    CONSTRAINT ck_payments_refunded  CHECK (amount_refunded_minor BETWEEN 0 AND amount_minor),
    CONSTRAINT ck_payments_currency  CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payments_status    CHECK (status IN ('CREATED','AUTHORIZED','CAPTURED','FAILED',
                                                       'REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED'))
);

CREATE INDEX ix_payments_order ON payments (order_id);
