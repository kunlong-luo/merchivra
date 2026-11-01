-- V53: cap LIVE catalog retry churn while preserving existing cursor/lease fencing.
-- Existing jobs can continue at zero; explicit owner restart resets the counter.
ALTER TABLE spapi_catalog_sync_job
  ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0);

CREATE INDEX spapi_catalog_sync_due_v53
  ON spapi_catalog_sync_job (state, retry_at, updated_at)
  WHERE state IN ('READY','RETRY','RUNNING');

CREATE INDEX spapi_finances_sync_due_v53
  ON spapi_finances_sync_job (state, retry_at, updated_at)
  WHERE state IN ('READY','RETRY','RUNNING');

CREATE INDEX orders_sync_due_v53
  ON orders_sync_job (mode, state, next_attempt_at, updated_at)
  WHERE state IN ('READY','WAITING_RETRY');
