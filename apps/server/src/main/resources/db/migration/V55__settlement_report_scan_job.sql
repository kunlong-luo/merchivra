-- V55: single active settlement discovery window per tenant/Marketplace.
-- Explicit Owner-started only; no automatic Seller enrollment or Amazon write.
CREATE TABLE spapi_settlement_scan_job (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  seller_account_id UUID NOT NULL,
  created_by UUID NOT NULL,
  window_after TIMESTAMPTZ NOT NULL,
  window_before TIMESTAMPTZ NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'READY'
    CHECK(state IN ('READY','RUNNING','RETRY','SUCCEEDED','PAUSED')),
  next_token TEXT,
  token_issued_at TIMESTAMPTZ,
  row_offset INTEGER NOT NULL DEFAULT 0 CHECK(row_offset BETWEEN 0 AND 1000),
  page_index INTEGER NOT NULL DEFAULT 0 CHECK(page_index >= 0),
  generation INTEGER NOT NULL DEFAULT 1 CHECK(generation > 0),
  lease_epoch BIGINT NOT NULL DEFAULT 0 CHECK(lease_epoch >= 0),
  lease_owner UUID,
  lease_expires_at TIMESTAMPTZ,
  retry_at TIMESTAMPTZ,
  retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count BETWEEN 0 AND 100),
  last_failure_code VARCHAR(48),
  imported_count INTEGER NOT NULL DEFAULT 0 CHECK(imported_count >= 0),
  completed_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,seller_marketplace_id),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,seller_account_id)
    REFERENCES seller_account(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by)
    REFERENCES membership(tenant_id,user_id),
  CHECK(window_before > window_after AND window_before <= window_after + interval '14 days'),
  CHECK((lease_owner IS NULL)=(lease_expires_at IS NULL)),
  CHECK(next_token IS NOT NULL OR token_issued_at IS NULL)
);
CREATE INDEX spapi_settlement_scan_due_v55 ON spapi_settlement_scan_job
  (state,retry_at,lease_expires_at,updated_at);
