-- =====================================================================
-- V8: Failure Simulation Lab history (demo/local only - rows exist only where
-- payguard.faults.enabled=true). Each run is a timeline of REAL backend steps,
-- each step with database evidence captured at that moment.
-- =====================================================================
CREATE TABLE simulation_runs (
    id            BIGSERIAL     PRIMARY KEY,
    scenario      VARCHAR(48)   NOT NULL,
    status        VARCHAR(16)   NOT NULL,      -- RUNNING | PASSED | FAILED | ERROR
    started_by    VARCHAR(64)   NOT NULL,
    steps         JSONB         NOT NULL DEFAULT '[]'::jsonb,
    invariants    JSONB         NOT NULL DEFAULT '[]'::jsonb,
    error         VARCHAR(1000),
    started_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    CONSTRAINT ck_simulation_status CHECK (status IN ('RUNNING','PASSED','FAILED','ERROR'))
);
CREATE INDEX ix_simulation_runs_started ON simulation_runs (started_at DESC);
