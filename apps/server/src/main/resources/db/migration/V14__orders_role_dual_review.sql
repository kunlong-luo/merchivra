-- V14: manual dual-control Orders role review. Nothing is auto-approved.
-- External application-role evidence is verified by a separate SECURITY_REVIEWER.
CREATE TABLE spapi_orders_role_review_request (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_account_id UUID NOT NULL,
  requested_by UUID NOT NULL,
  role_code VARCHAR(48) NOT NULL DEFAULT 'Inventory and Order Tracking'
    CHECK (role_code = 'Inventory and Order Tracking'),
  evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  external_case_ref VARCHAR(128) NOT NULL CHECK(length(trim(external_case_ref)) >= 8),
  grant_verified_at TIMESTAMPTZ NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'PENDING'
    CHECK (state IN ('PENDING','APPROVED','REJECTED','STALE')),
  reviewed_by UUID,
  reviewed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,seller_account_id)
    REFERENCES spapi_seller_authorization(tenant_id,seller_account_id),
  FOREIGN KEY(tenant_id,requested_by)
    REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by)
    REFERENCES membership(tenant_id,user_id),
  CHECK((reviewed_by IS NULL AND reviewed_at IS NULL) =
        (state IN ('PENDING','STALE'))),
  CHECK(reviewed_by IS NULL OR reviewed_by <> requested_by)
);
CREATE UNIQUE INDEX spapi_orders_review_one_pending
  ON spapi_orders_role_review_request(tenant_id,seller_account_id)
  WHERE state='PENDING';

CREATE TABLE spapi_orders_role_review_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  review_id UUID NOT NULL,
  actor_id UUID NOT NULL,
  event_code VARCHAR(24) NOT NULL
    CHECK(event_code IN ('REQUESTED','APPROVED','REJECTED','REVOKED','STALE')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,review_id)
    REFERENCES spapi_orders_role_review_request(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX spapi_orders_role_review_audit_recent
  ON spapi_orders_role_review_audit(tenant_id,created_at DESC);
