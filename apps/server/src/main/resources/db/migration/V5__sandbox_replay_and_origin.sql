-- Opt-in, local-only simulated SP-API ingestion. No live credentials or financial facts.
-- Every ingested row identifies its synthetic provenance; do not repurpose for live Seller data.
ALTER TABLE marketplace_listing ADD COLUMN data_origin VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED'
  CHECK (data_origin IN ('UNVERIFIED','MOCK'));
ALTER TABLE sales_order ADD COLUMN data_origin VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED'
  CHECK (data_origin IN ('UNVERIFIED','MOCK'));
ALTER TABLE order_item ADD COLUMN data_origin VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED'
  CHECK (data_origin IN ('UNVERIFIED','MOCK'));
ALTER TABLE fba_inventory_observation ADD COLUMN data_origin VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED'
  CHECK (data_origin IN ('UNVERIFIED','MOCK'));

CREATE TABLE sandbox_sync_run (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_fingerprint VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED_RETRYABLE')),
  source VARCHAR(16) NOT NULL DEFAULT 'MOCK' CHECK (source = 'MOCK'),
  order_page_count INTEGER NOT NULL DEFAULT 0 CHECK (order_page_count >= 0),
  listing_count INTEGER NOT NULL DEFAULT 0 CHECK (listing_count >= 0),
  order_count INTEGER NOT NULL DEFAULT 0 CHECK (order_count >= 0),
  inventory_count INTEGER NOT NULL DEFAULT 0 CHECK (inventory_count >= 0),
  failure_code VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, seller_marketplace_id, idempotency_key),
  FOREIGN KEY (tenant_id, actor_user_id) REFERENCES membership(tenant_id, user_id),
  FOREIGN KEY (tenant_id, seller_marketplace_id) REFERENCES seller_marketplace(tenant_id, id)
);
CREATE INDEX sandbox_sync_run_recent_idx
  ON sandbox_sync_run (tenant_id, seller_marketplace_id, created_at DESC, id DESC);

-- Only local setup audit: add an explicit distinct simulated-import action.
ALTER TABLE local_setup_audit DROP CONSTRAINT local_setup_audit_action_check;
ALTER TABLE local_setup_audit ADD CONSTRAINT local_setup_audit_action_check
 CHECK (action IN ('LOCAL_SELLER_ACCOUNT_CREATED','LOCAL_MARKETPLACE_ADDED',
                   'SANDBOX_FIXTURE_IMPORTED'));
