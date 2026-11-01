-- V58: independent dual-control review of MANUAL_UNVERIFIED bank evidence.
ALTER TABLE spapi_settlement_report
  ADD CONSTRAINT settlement_report_tenant_market_id_v58
  UNIQUE(tenant_id,seller_marketplace_id,id);
ALTER TABLE bank_payout_observation
  ADD CONSTRAINT bank_payout_tenant_market_id_v58
  UNIQUE(tenant_id,seller_marketplace_id,id);
CREATE TABLE bank_payout_manual_review (
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  report_id UUID NOT NULL,
  payout_entry_id UUID NOT NULL,
  state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','REVIEWED','REJECTED')),
  evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  requested_by UUID NOT NULL,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_by UUID,
  reviewed_at TIMESTAMPTZ,
  version INTEGER NOT NULL DEFAULT 1 CHECK(version>0),
  PRIMARY KEY(tenant_id,report_id),
  UNIQUE(tenant_id,payout_entry_id),
  FOREIGN KEY(tenant_id,seller_marketplace_id,report_id)
    REFERENCES spapi_settlement_report(tenant_id,seller_marketplace_id,id),
  FOREIGN KEY(tenant_id,seller_marketplace_id,payout_entry_id)
    REFERENCES bank_payout_observation(tenant_id,seller_marketplace_id,id),
  FOREIGN KEY(tenant_id,requested_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
  CHECK((state='PENDING')=(reviewed_by IS NULL AND reviewed_at IS NULL)),
  CHECK(reviewed_by IS NULL OR reviewed_by<>requested_by)
);
CREATE TABLE bank_payout_manual_review_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  report_id UUID NOT NULL,
  review_version INTEGER NOT NULL CHECK(review_version>0),
  actor_user_id UUID NOT NULL,
  action VARCHAR(20) NOT NULL
    CHECK(action IN ('REQUESTED','REOPENED','REVIEWED','REJECTED')),
  evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  reason VARCHAR(500) NOT NULL CHECK(length(trim(reason)) BETWEEN 10 AND 500),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,report_id)
    REFERENCES bank_payout_manual_review(tenant_id,report_id),
  FOREIGN KEY(tenant_id,actor_user_id)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX bank_payout_manual_audit_latest_v58
  ON bank_payout_manual_review_audit(tenant_id,report_id,occurred_at DESC,id);
