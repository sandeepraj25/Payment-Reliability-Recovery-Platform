-- Plain-SQL checks of the Phase 1 schema invariants.
-- Run against a freshly migrated database:
--   psql -v ON_ERROR_STOP=1 -f src/test/sql/schema_checks.sql
-- Every check raises an exception (and stops the script) if an invariant is broken.
-- Everything runs in one transaction that is rolled back at the end.

BEGIN;

CREATE OR REPLACE FUNCTION pg_temp.expect_error(sql TEXT, expected_state TEXT, label TEXT) RETURNS VOID AS $$
BEGIN
    EXECUTE sql;
    RAISE EXCEPTION 'FAIL: % - statement succeeded but should have failed', label;
EXCEPTION
    WHEN OTHERS THEN
        IF SQLSTATE = 'P0001' THEN RAISE; END IF;          -- our own FAIL above
        IF SQLSTATE <> expected_state THEN
            RAISE EXCEPTION 'FAIL: % - expected SQLSTATE %, got % (%)', label, expected_state, SQLSTATE, SQLERRM;
        END IF;
        RAISE NOTICE 'PASS: % (rejected with %)', label, SQLSTATE;
END;
$$ LANGUAGE plpgsql;

-- 23514 = check_violation, 23505 = unique_violation, 23503 = foreign_key_violation, 22P02 = invalid_text_representation

-- ---------- money ----------
SELECT pg_temp.expect_error($q$INSERT INTO orders (id, customer_ref, amount_minor, currency, status)
    VALUES (gen_random_uuid(), 'c1', 0, 'INR', 'PAYMENT_PENDING')$q$, '23514', 'zero amount rejected');
SELECT pg_temp.expect_error($q$INSERT INTO orders (id, customer_ref, amount_minor, currency, status)
    VALUES (gen_random_uuid(), 'c1', -5, 'INR', 'PAYMENT_PENDING')$q$, '23514', 'negative amount rejected');
-- GOTCHA (found while validating this schema): PostgreSQL does NOT reject a
-- fractional numeric literal for a BIGINT column - the assignment cast ROUNDS it.
-- 4999.50 is silently stored as 5000. So the database is not our defense against
-- fractional money; the API layer must reject it (Java long + Jackson
-- ACCEPT_FLOAT_AS_INT=false, see application.yml). This check documents the behaviour.
DO $$
DECLARE stored BIGINT;
BEGIN
    INSERT INTO orders (id, customer_ref, amount_minor, currency, status)
    VALUES ('22222222-2222-2222-2222-222222222222', 'c1', 4999.50, 'INR', 'PAYMENT_PENDING')
    RETURNING amount_minor INTO stored;
    IF stored <> 5000 THEN RAISE EXCEPTION 'FAIL: expected silent rounding to 5000, got %', stored; END IF;
    RAISE NOTICE 'DOCUMENTED: BIGINT column silently rounded 4999.50 to % - reject fractions in the API layer', stored;
    DELETE FROM orders WHERE id = '22222222-2222-2222-2222-222222222222';
END $$;
SELECT pg_temp.expect_error($q$INSERT INTO orders (id, customer_ref, amount_minor, currency, status)
    VALUES (gen_random_uuid(), 'c1', 100, 'inr', 'PAYMENT_PENDING')$q$, '23514', 'lowercase currency rejected');

-- ---------- order fulfilment consistency ----------
SELECT pg_temp.expect_error($q$INSERT INTO orders (id, customer_ref, amount_minor, currency, status)
    VALUES (gen_random_uuid(), 'c1', 100, 'INR', 'FULFILLED')$q$, '23514', 'FULFILLED without fulfilled_at rejected');
SELECT pg_temp.expect_error($q$INSERT INTO orders (id, customer_ref, amount_minor, currency, status, fulfilled_at)
    VALUES (gen_random_uuid(), 'c1', 100, 'INR', 'PAID', now())$q$, '23514', 'fulfilled_at on non-FULFILLED order rejected');

-- ---------- payments ----------
INSERT INTO orders (id, customer_ref, amount_minor, currency, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'c1', 499900, 'INR', 'PAYMENT_PENDING');

SELECT pg_temp.expect_error($q$INSERT INTO payments (id, order_id, provider, amount_minor, currency, status)
    VALUES (gen_random_uuid(), gen_random_uuid(), 'payguard-sim', 100, 'INR', 'CREATED')$q$, '23503', 'payment for unknown order rejected');
SELECT pg_temp.expect_error($q$INSERT INTO payments (id, order_id, provider, amount_minor, currency, status)
    VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'payguard-sim', 100, 'INR', 'SETTLED')$q$, '23514', 'unknown payment status rejected');
SELECT pg_temp.expect_error($q$INSERT INTO payments (id, order_id, provider, amount_minor, amount_refunded_minor, currency, status)
    VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'payguard-sim', 100, 101, 'INR', 'REFUNDED')$q$, '23514', 'refund larger than amount rejected');

-- Two payments with NULL gateway id are allowed (both still waiting for gateway confirmation)
INSERT INTO payments (id, order_id, provider, amount_minor, currency, status)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'payguard-sim', 499900, 'INR', 'CREATED'),
       (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'payguard-sim', 499900, 'INR', 'CREATED');
DO $$ BEGIN RAISE NOTICE 'PASS: multiple NULL gateway_payment_id rows allowed'; END $$;

-- A captured double charge CAN be recorded (reconciliation must see it), see V2 comment
INSERT INTO payments (id, order_id, provider, gateway_payment_id, amount_minor, currency, status)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'payguard-sim', 'pay_A', 499900, 'INR', 'CAPTURED'),
       (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'payguard-sim', 'pay_B', 499900, 'INR', 'CAPTURED');
DO $$ BEGIN RAISE NOTICE 'PASS: two captured payments for one order can be recorded'; END $$;

SELECT pg_temp.expect_error($q$INSERT INTO payments (id, order_id, provider, gateway_payment_id, amount_minor, currency, status)
    VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'payguard-sim', 'pay_A', 499900, 'INR', 'CREATED')$q$,
    '23505', 'same gateway payment id mapped twice rejected');

-- ---------- webhook events ----------
INSERT INTO webhook_events (provider, provider_event_id, event_type, gateway_payment_id, raw_body, payload, payload_sha256)
VALUES ('payguard-sim', 'evt_1', 'payment.captured', 'pay_A', '{"id":"evt_1"}', '{"id":"evt_1"}', repeat('a', 64));

SELECT pg_temp.expect_error($q$INSERT INTO webhook_events (provider, provider_event_id, event_type, gateway_payment_id, raw_body, payload, payload_sha256)
    VALUES ('payguard-sim', 'evt_1', 'payment.captured', 'pay_A', '{}', '{}', repeat('b', 64))$q$, '23505', 'duplicate (provider, event_id) rejected');

-- Same event id from a DIFFERENT provider is a different event
INSERT INTO webhook_events (provider, provider_event_id, event_type, gateway_payment_id, raw_body, payload, payload_sha256)
VALUES ('other-psp', 'evt_1', 'payment.captured', 'x_1', '{}', '{}', repeat('c', 64));
DO $$ BEGIN RAISE NOTICE 'PASS: same event id under another provider allowed'; END $$;

SELECT pg_temp.expect_error($q$INSERT INTO webhook_events (provider, provider_event_id, event_type, raw_body, payload, payload_sha256)
    VALUES ('payguard-sim', 'evt_2', 'payment.captured', '{}', '{}', repeat('d', 64))$q$, '23514', 'known event without payment id rejected');

INSERT INTO webhook_events (provider, provider_event_id, event_type, raw_body, payload, payload_sha256, processing_status)
VALUES ('payguard-sim', 'evt_3', 'account.updated', '{}', '{}', repeat('e', 64), 'IGNORED');
DO $$ BEGIN RAISE NOTICE 'PASS: unknown event type stored as IGNORED without payment id'; END $$;

-- ---------- idempotency ----------
INSERT INTO idempotency_records (scope, idempotency_key, request_fingerprint, status, expires_at)
VALUES ('POST /api/checkout', 'k1', repeat('f', 64), 'IN_PROGRESS', now() + interval '1 day');
SELECT pg_temp.expect_error($q$INSERT INTO idempotency_records (scope, idempotency_key, request_fingerprint, status, expires_at)
    VALUES ('POST /api/checkout', 'k1', repeat('f', 64), 'IN_PROGRESS', now() + interval '1 day')$q$, '23505', 'duplicate idempotency key rejected');
SELECT pg_temp.expect_error($q$UPDATE idempotency_records SET status = 'COMPLETED' WHERE idempotency_key = 'k1'$q$,
    '23514', 'COMPLETED without stored response rejected');

-- ---------- gateway ----------
INSERT INTO gateway.gateway_payments (id, gateway_reference, merchant_order_ref, amount_minor, currency, status,
                                      create_idempotency_key, create_request_fingerprint)
VALUES ('pay_G1', 'grf_1', 'order-1', 1000, 'INR', 'CAPTURED', 'gk1', repeat('0', 64));
SELECT pg_temp.expect_error($q$INSERT INTO gateway.gateway_payments (id, gateway_reference, merchant_order_ref, amount_minor, currency, status,
        create_idempotency_key, create_request_fingerprint)
    VALUES ('pay_G2', 'grf_2', 'order-1', 1000, 'INR', 'CREATED', 'gk1', repeat('0', 64))$q$, '23505', 'gateway create key reused rejected');
INSERT INTO gateway.gateway_refunds (id, payment_id, amount_minor, idempotency_key, request_fingerprint)
VALUES ('rfnd_1', 'pay_G1', 500, 'rk1', repeat('0', 64));
SELECT pg_temp.expect_error($q$INSERT INTO gateway.gateway_refunds (id, payment_id, amount_minor, idempotency_key, request_fingerprint)
    VALUES ('rfnd_2', 'pay_G1', 500, 'rk1', repeat('0', 64))$q$, '23505', 'duplicate refund key rejected');
INSERT INTO gateway.gateway_events (id, payment_id, event_type, payment_version, payload)
VALUES ('evt_G1', 'pay_G1', 'payment.captured', 2, '{}');
SELECT pg_temp.expect_error($q$INSERT INTO gateway.gateway_events (id, payment_id, event_type, payment_version, payload)
    VALUES ('evt_G2', 'pay_G1', 'payment.captured', 2, '{}')$q$, '23505', 'two events for the same payment version rejected');

DO $$ BEGIN RAISE NOTICE 'ALL SCHEMA CHECKS PASSED'; END $$;
ROLLBACK;
