-- Pre-production OAuth handoff and encrypted Seller refresh-token vault.
-- No plaintext tokens, OAuth code, Amazon callback URL or Amazon state in SQL.
CREATE TABLE spapi_oauth_attempt (
  state_hash CHAR(64) PRIMARY KEY,
  tenant_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  seller_account_id UUID NOT NULL,
  expected_seller_id VARCHAR(128),
  status VARCHAR(24) NOT NULL DEFAULT 'STARTED'
    CHECK (status IN ('STARTED','HANDOFF','CLAIMED','EXPIRED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  FOREIGN KEY (tenant_id, actor_user_id) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY (tenant_id, seller_account_id) REFERENCES seller_account(tenant_id,id),
  CHECK (expires_at > created_at)
);
CREATE INDEX spapi_oauth_attempt_expiry_idx ON spapi_oauth_attempt (expires_at);

CREATE TABLE spapi_seller_authorization (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_account_id UUID NOT NULL,
  seller_id VARCHAR(128) NOT NULL,
  region VARCHAR(4) NOT NULL CHECK (region IN ('NA','EU','FE')),
  status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING_VERIFICATION','VERIFIED','REVOKED')),
  ciphertext BYTEA,
  nonce BYTEA,
  key_version INTEGER NOT NULL DEFAULT 1 CHECK (key_version > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at TIMESTAMPTZ,
  UNIQUE (tenant_id, seller_account_id),
  FOREIGN KEY (tenant_id, seller_account_id) REFERENCES seller_account(tenant_id,id),
  CHECK ((ciphertext IS NULL) = (nonce IS NULL)),
  CHECK ((status = 'REVOKED' AND ciphertext IS NULL AND nonce IS NULL)
      OR (status <> 'REVOKED' AND ciphertext IS NOT NULL AND nonce IS NOT NULL))
);
-- One active seller authorization per tenant, Seller ID and region.
CREATE UNIQUE INDEX spapi_seller_auth_active_seller
 ON spapi_seller_authorization (tenant_id, seller_id, region)
 WHERE status <> 'REVOKED';

CREATE TABLE spapi_oauth_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  actor_user_id UUID,
  seller_account_id UUID NOT NULL,
  event_type VARCHAR(40) NOT NULL CHECK (event_type IN
     ('STARTED','HANDOFF','CODE_EXCHANGED','EXCHANGE_FAILED','REVOKED')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, seller_account_id) REFERENCES seller_account(tenant_id,id)
);
CREATE INDEX spapi_oauth_audit_tenant_recent ON spapi_oauth_audit (tenant_id, occurred_at DESC);
