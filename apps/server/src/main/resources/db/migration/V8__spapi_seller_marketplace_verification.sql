-- Seller authorization stays blocked until the Sellers v1 check succeeds.
-- A short claim token prevents an old network response from reviving a revoked grant.
ALTER TABLE spapi_seller_authorization
  DROP CONSTRAINT spapi_seller_authorization_status_check;
ALTER TABLE spapi_seller_authorization
  ADD CONSTRAINT spapi_seller_authorization_status_check
  CHECK (status IN ('PENDING_VERIFICATION','VERIFYING','VERIFICATION_FAILED','VERIFIED','REVOKED')),
  ADD COLUMN verification_attempt_id UUID,
  ADD COLUMN verification_started_at TIMESTAMPTZ,
  ADD COLUMN verification_error_code VARCHAR(40),
  ADD COLUMN verified_at TIMESTAMPTZ;

CREATE TABLE spapi_marketplace_verification (
  tenant_id UUID NOT NULL,
  seller_account_id UUID NOT NULL,
  marketplace_id VARCHAR(64) NOT NULL,
  verified_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id,seller_account_id,marketplace_id),
  FOREIGN KEY (tenant_id,seller_account_id,marketplace_id)
    REFERENCES seller_marketplace(tenant_id,seller_account_id,marketplace_id),
  FOREIGN KEY (tenant_id,seller_account_id)
    REFERENCES spapi_seller_authorization(tenant_id,seller_account_id)
);

ALTER TABLE spapi_oauth_audit DROP CONSTRAINT spapi_oauth_audit_event_type_check;
ALTER TABLE spapi_oauth_audit ADD CONSTRAINT spapi_oauth_audit_event_type_check
 CHECK (event_type IN ('STARTED','HANDOFF','CODE_EXCHANGED','EXCHANGE_FAILED',
    'REVOKED','VERIFICATION_STARTED','VERIFICATION_FAILED','VERIFIED'));
