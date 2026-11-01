-- V56: maker-checker review of independent Amazon Reports vs Finances source evidence.
-- This is NOT a bank payout, ledger posting, or Amazon settlement confirmation.
CREATE TABLE finance_settlement_source_review (
  tenant_id UUID NOT NULL,
  report_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','APPROVED','REJECTED')),
  source_state VARCHAR(48) NOT NULL,
  evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  requested_by UUID NOT NULL,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_by UUID,
  decided_at TIMESTAMPTZ,
  version INTEGER NOT NULL DEFAULT 1 CHECK(version>0),
  PRIMARY KEY(tenant_id,report_id),
  FOREIGN KEY(tenant_id,report_id) REFERENCES spapi_settlement_report(tenant_id,id),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,requested_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,decided_by) REFERENCES membership(tenant_id,user_id),
  CHECK((state='PENDING')=(decided_by IS NULL AND decided_at IS NULL)),
  CHECK(decided_by IS NULL OR decided_by<>requested_by)
);
CREATE TABLE finance_settlement_source_review_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  report_id UUID NOT NULL,
  review_version INTEGER NOT NULL CHECK(review_version>0),
  action VARCHAR(24) NOT NULL
    CHECK(action IN ('REQUESTED','REOPENED','APPROVED','REJECTED')),
  source_state VARCHAR(48) NOT NULL,
  evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  actor_user_id UUID NOT NULL,
  reason VARCHAR(500) NOT NULL CHECK(length(trim(reason)) BETWEEN 10 AND 500),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,report_id)
    REFERENCES finance_settlement_source_review(tenant_id,report_id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_settlement_source_review_audit_recent
  ON finance_settlement_source_review_audit(tenant_id,report_id,occurred_at DESC,id);
