-- M3 prototype: one durable *mock* Orders page per explicit HTTP request.
-- No live Amazon token/PII. Transaction row-lock is not a background Worker lease.
CREATE TABLE sandbox_page_stream (
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  next_page INTEGER NOT NULL DEFAULT 0 CHECK (next_page >= 0),
  total_pages INTEGER NOT NULL CHECK (total_pages >= 0),
  lease_epoch BIGINT NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
  completed_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, seller_marketplace_id),
  FOREIGN KEY (tenant_id, seller_marketplace_id) REFERENCES seller_marketplace(tenant_id, id),
  CHECK (completed_at IS NULL OR next_page = total_pages)
);

CREATE TABLE sandbox_page_commit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  page_index INTEGER NOT NULL CHECK (page_index >= 0),
  total_pages INTEGER NOT NULL CHECK (total_pages >= 1),
  record_count INTEGER NOT NULL CHECK (record_count >= 0),
  lease_epoch BIGINT NOT NULL CHECK (lease_epoch >= 1),
  committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, seller_marketplace_id, idempotency_key),
  UNIQUE (tenant_id, seller_marketplace_id, page_index),
  FOREIGN KEY (tenant_id, seller_marketplace_id)
    REFERENCES sandbox_page_stream(tenant_id, seller_marketplace_id)
);
CREATE INDEX sandbox_page_commit_recent
  ON sandbox_page_commit (tenant_id, seller_marketplace_id, page_index);
