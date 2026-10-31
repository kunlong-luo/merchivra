-- V35: audited item attribution of the already reviewed PRODUCT_REFUND portion.
-- Not an Amazon refund edit, bank posting, inventory receipt or COGS reversal.
CREATE TABLE finance_refund_amount_allocation (
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  external_item_id VARCHAR(160) NOT NULL,
  signed_amount NUMERIC(20,6) NOT NULL CHECK(signed_amount <> 0),
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  reason_reference VARCHAR(160) NOT NULL
    CHECK(reason_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
  revision INTEGER NOT NULL CHECK(revision > 0),
  reviewed_by UUID NOT NULL,
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id,event_id,external_item_id),
  FOREIGN KEY (tenant_id,event_id,external_item_id)
    REFERENCES finance_refund_item_review(tenant_id,event_id,external_item_id),
  FOREIGN KEY (tenant_id,reviewed_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE finance_refund_amount_allocation_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  external_item_id VARCHAR(160) NOT NULL,
  action VARCHAR(24) NOT NULL CHECK(action IN ('CREATED','UPDATED','WITHDRAWN')),
  signed_amount NUMERIC(20,6) NOT NULL,
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  reason_reference VARCHAR(160) NOT NULL,
  revision INTEGER NOT NULL CHECK(revision>0),
  actor_user_id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,event_id) REFERENCES finance_source_event(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_refund_amount_audit_recent
 ON finance_refund_amount_allocation_audit(tenant_id,event_id,occurred_at DESC,id DESC);
