-- =====================================================================
-- V7: a mismatch is identified per run by WHAT it is about. Event-level findings
-- (duplicate deliveries, dead letters) and order-level findings (double charge) need
-- the event id / order id in the key, or two findings for the same payment collide.
-- Forward fix rather than editing V6 (V6 has already been applied by CI databases).
-- =====================================================================
ALTER TABLE reconciliation_mismatches ADD COLUMN provider_event_id VARCHAR(128);
ALTER TABLE reconciliation_mismatches DROP CONSTRAINT uq_mismatch_per_run;
ALTER TABLE reconciliation_mismatches ADD CONSTRAINT uq_mismatch_per_run UNIQUE NULLS NOT DISTINCT
    (run_id, mismatch_type, gateway_payment_id, merchant_payment_id, order_id, provider_event_id);
