-- V61 / product milestone V63: independent review of MANUAL return/COGS sensitivity.
-- This is NOT a verified physical return, stock receipt, expense reversal or ledger entry.
CREATE TABLE finance_refund_recovery_review (
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 event_id UUID NOT NULL,
 sales_order_id UUID NOT NULL,
 state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','REVIEWED','REJECTED')),
 source_evidence_sha256 CHAR(64) NOT NULL CHECK(source_evidence_sha256 ~ '^[0-9a-f]{64}$'),
 evidence_origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL_UNVERIFIED'
   CHECK(evidence_origin='MANUAL_UNVERIFIED'),
 revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),
 requested_by UUID NOT NULL,
 requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 decided_by UUID,
 decided_at TIMESTAMPTZ,
 PRIMARY KEY(tenant_id,event_id),
 UNIQUE(tenant_id,event_id,sales_order_id),
 FOREIGN KEY(tenant_id,seller_marketplace_id,event_id)
   REFERENCES finance_source_event(tenant_id,seller_marketplace_id,id),
 FOREIGN KEY(tenant_id,seller_marketplace_id,sales_order_id)
   REFERENCES sales_order(tenant_id,seller_marketplace_id,id),
 FOREIGN KEY(tenant_id,requested_by) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,decided_by) REFERENCES membership(tenant_id,user_id),
 CHECK(requested_by IS DISTINCT FROM decided_by),
 CHECK((state='PENDING')=(decided_by IS NULL AND decided_at IS NULL))
);
CREATE TABLE finance_refund_recovery_audit (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 event_id UUID NOT NULL,
 revision INTEGER NOT NULL CHECK(revision>0),
 actor_user_id UUID NOT NULL,
 action VARCHAR(24) NOT NULL
   CHECK(action IN ('REQUESTED','REOPENED','REVIEWED','REJECTED')),
 source_evidence_sha256 CHAR(64) NOT NULL CHECK(source_evidence_sha256 ~ '^[0-9a-f]{64}$'),
 reason VARCHAR(500) NOT NULL CHECK(length(btrim(reason)) BETWEEN 10 AND 500),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,event_id) REFERENCES finance_refund_recovery_review(tenant_id,event_id),
 FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_refund_recovery_audit_recent_v61
 ON finance_refund_recovery_audit(tenant_id,event_id,occurred_at DESC,id);
CREATE INDEX finance_refund_recovery_order_v61
 ON finance_refund_recovery_review(tenant_id,seller_marketplace_id,sales_order_id);
