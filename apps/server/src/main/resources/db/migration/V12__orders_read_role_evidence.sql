-- V12: fail-closed, operation-specific Orders v2026 read role evidence.
-- Sellers marketplace participation does NOT prove Amazon application roles.
-- No automatic evidence insertion, no public attestation endpoint.
CREATE TABLE spapi_orders_role_evidence (
  tenant_id UUID NOT NULL,
  seller_account_id UUID NOT NULL,
  role_code VARCHAR(48) NOT NULL
    CHECK (role_code = 'Inventory and Order Tracking'),
  evidence_kind VARCHAR(40) NOT NULL
    CHECK (evidence_kind = 'EXTERNAL_APP_ROLE_REVIEW'),
  evidence_sha256 CHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
  grant_verified_at TIMESTAMPTZ NOT NULL,
  reviewed_by UUID NOT NULL,
  reviewed_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, seller_account_id, role_code),
  FOREIGN KEY (tenant_id, seller_account_id)
    REFERENCES spapi_seller_authorization(tenant_id, seller_account_id),
  FOREIGN KEY (tenant_id, reviewed_by)
    REFERENCES membership(tenant_id, user_id),
  CHECK (expires_at > reviewed_at),
  CHECK (expires_at <= reviewed_at + interval '30 days'),
  CHECK (revoked_at IS NULL OR revoked_at >= reviewed_at)
);
CREATE INDEX spapi_orders_role_expiry_idx
  ON spapi_orders_role_evidence (expires_at) WHERE revoked_at IS NULL;
