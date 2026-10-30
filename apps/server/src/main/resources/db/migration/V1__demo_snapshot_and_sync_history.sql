-- M1 demonstration storage only: never use these tables for live Amazon seller data.
-- This migration is applied to all environments but seeded/read/written ONLY when
-- MERCHIVRA_DEMO_ENABLED=true; the demo controller is disabled by default.
CREATE TABLE demo_snapshot (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE demo_sync_run (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL
      CHECK (status IN ('SUCCEEDED', 'FAILED_RETRYABLE')),
    source VARCHAR(16) NOT NULL DEFAULT 'MOCK' CHECK (source = 'MOCK'),
    fixture_count INTEGER NOT NULL DEFAULT 0 CHECK (fixture_count >= 0),
    failure_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX demo_sync_run_recent_idx ON demo_sync_run (started_at DESC, id DESC);
