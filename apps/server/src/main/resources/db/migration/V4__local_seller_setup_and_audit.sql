-- Local setup-only account metadata. Does not grant Amazon API access.
-- V1-V3 migrations are immutable; demo/imported rows receive a neutral label.
ALTER TABLE seller_account
  ADD COLUMN display_name VARCHAR(120) NOT NULL DEFAULT 'Imported account',
  ADD COLUMN setup_request_key VARCHAR(128);
CREATE UNIQUE INDEX seller_account_local_setup_request_unique
  ON seller_account (tenant_id, setup_request_key)
  WHERE setup_request_key IS NOT NULL;

-- Append-only application audit trail for local account/marketplace setup.
-- No credentials, PII payloads or request body are stored in this table.
CREATE TABLE local_setup_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  action VARCHAR(48) NOT NULL
    CHECK (action IN ('LOCAL_SELLER_ACCOUNT_CREATED','LOCAL_MARKETPLACE_ADDED')),
  resource_type VARCHAR(32) NOT NULL CHECK (resource_type IN ('SELLER_ACCOUNT','MARKETPLACE')),
  resource_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, actor_user_id) REFERENCES membership(tenant_id, user_id)
);
CREATE INDEX local_setup_audit_tenant_recent_idx
  ON local_setup_audit (tenant_id, created_at DESC, id DESC);
