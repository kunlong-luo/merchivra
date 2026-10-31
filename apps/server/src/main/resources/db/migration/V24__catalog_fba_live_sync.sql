-- V24: independently gated LIVE read-only Listings/FBA page sync.
-- Unknown observations are never filled with zero and no absent Listing is deleted.
CREATE TABLE spapi_catalog_sync_job (
  id UUID NOT NULL UNIQUE,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  stream VARCHAR(16) NOT NULL CHECK (stream IN ('LISTINGS','FBA')),
  state VARCHAR(24) NOT NULL DEFAULT 'READY'
    CHECK (state IN ('READY','RUNNING','RETRY','PAUSED','SUCCEEDED')),
  next_token TEXT,
  token_issued_at TIMESTAMPTZ,
  generation INTEGER NOT NULL DEFAULT 1 CHECK (generation>0),
  page_index INTEGER NOT NULL DEFAULT 0 CHECK (page_index>=0),
  lease_epoch BIGINT NOT NULL DEFAULT 0 CHECK (lease_epoch>=0),
  lease_owner UUID,
  lease_expires_at TIMESTAMPTZ,
  retry_at TIMESTAMPTZ,
  last_failure_code VARCHAR(40),
  last_finished_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id,seller_marketplace_id,stream),
  UNIQUE (tenant_id,id),
  FOREIGN KEY (tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id),
  CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL)),
  CHECK ((next_token IS NULL) = (token_issued_at IS NULL))
);
CREATE TABLE spapi_catalog_page_commit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  job_id UUID NOT NULL,
  generation INTEGER NOT NULL CHECK (generation>0),
  page_index INTEGER NOT NULL CHECK (page_index>=0),
  record_count INTEGER NOT NULL CHECK (record_count>=0),
  committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id,job_id,generation,page_index),
  FOREIGN KEY (tenant_id,job_id)
    REFERENCES spapi_catalog_sync_job(tenant_id,id)
);
CREATE INDEX spapi_catalog_sync_recent ON spapi_catalog_sync_job(tenant_id,updated_at DESC);
