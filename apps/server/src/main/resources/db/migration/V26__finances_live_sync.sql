-- V26: read-only Finances v2024-06-19 pagination checkpoint.
-- Source transaction total is classified UNCLASSIFIED, never automatically income.
CREATE TABLE spapi_finances_sync_job (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'READY'
    CHECK(state IN ('READY','RUNNING','RETRY','SUCCEEDED','PAUSED')),
  posted_after TIMESTAMPTZ NOT NULL,
  posted_before TIMESTAMPTZ NOT NULL,
  next_token TEXT,
  token_issued_at TIMESTAMPTZ,
  page_index INTEGER NOT NULL DEFAULT 0 CHECK(page_index>=0),
  generation INTEGER NOT NULL DEFAULT 1 CHECK(generation>0),
  lease_epoch BIGINT NOT NULL DEFAULT 0 CHECK(lease_epoch>=0),
  lease_owner UUID,
  lease_expires_at TIMESTAMPTZ,
  retry_at TIMESTAMPTZ,
  retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count>=0),
  last_failure_code VARCHAR(48),
  completed_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK(posted_before>posted_after),
  CHECK((lease_owner IS NULL)=(lease_expires_at IS NULL)),
  CHECK((next_token IS NULL)=(token_issued_at IS NULL)),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,seller_marketplace_id),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id)
);
CREATE TABLE spapi_finances_page_commit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  job_id UUID NOT NULL,
  generation INTEGER NOT NULL CHECK(generation>0),
  page_index INTEGER NOT NULL CHECK(page_index>=0),
  event_count INTEGER NOT NULL CHECK(event_count>=0),
  committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,job_id,generation,page_index),
  FOREIGN KEY(tenant_id,job_id) REFERENCES spapi_finances_sync_job(tenant_id,id)
);
CREATE INDEX spapi_finances_due ON spapi_finances_sync_job(state,retry_at,updated_at);
