-- V34: refund item disposition is MANUAL_UNVERIFIED review evidence, not Amazon returns/stock receipt.
-- Never write a cost reversal to financial books from these rows.
CREATE TABLE finance_refund_item_review (
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  sales_order_id UUID NOT NULL,
  external_item_id VARCHAR(160) NOT NULL,
  reviewed_quantity INTEGER NOT NULL CHECK (reviewed_quantity > 0),
  disposition VARCHAR(24) NOT NULL
    CHECK (disposition IN ('RESTOCKED_CLAIMED','NOT_RESTOCKED','UNKNOWN')),
  evidence_reference VARCHAR(160) NOT NULL
    CHECK (evidence_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
  evidence_origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL_UNVERIFIED'
    CHECK (evidence_origin='MANUAL_UNVERIFIED'),
  revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),
  reviewed_by UUID NOT NULL,
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,event_id,external_item_id),
  FOREIGN KEY(tenant_id,event_id) REFERENCES finance_source_event(tenant_id,id),
  FOREIGN KEY(tenant_id,sales_order_id,external_item_id)
    REFERENCES order_item(tenant_id,sales_order_id,external_item_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_refund_review_order_idx
  ON finance_refund_item_review(tenant_id,sales_order_id,external_item_id);

CREATE TABLE finance_refund_review_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  sales_order_id UUID NOT NULL,
  external_item_id VARCHAR(160) NOT NULL,
  actor_user_id UUID NOT NULL,
  action VARCHAR(24) NOT NULL CHECK(action IN ('CREATED','UPDATED','WITHDRAWN')),
  reviewed_quantity INTEGER NOT NULL CHECK(reviewed_quantity>0),
  disposition VARCHAR(24) NOT NULL CHECK(disposition IN
    ('RESTOCKED_CLAIMED','NOT_RESTOCKED','UNKNOWN')),
  evidence_reference VARCHAR(160) NOT NULL,
  revision INTEGER NOT NULL CHECK(revision>0),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,event_id) REFERENCES finance_source_event(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_refund_review_audit_recent_idx
  ON finance_refund_review_audit(tenant_id,event_id,occurred_at DESC,id DESC);
