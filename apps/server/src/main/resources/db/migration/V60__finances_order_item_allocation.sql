-- V60 / V62: reviewed per-item attribution of fully reconciled LIVE Finances.
-- Does not post ledger entries, allocate refunds, mutate Amazon or verify payout.
ALTER TABLE finance_source_event
  ADD CONSTRAINT finances_tenant_market_event_v60
    UNIQUE(tenant_id,seller_marketplace_id,id);
ALTER TABLE sales_order
  ADD CONSTRAINT sales_order_tenant_market_id_v60
    UNIQUE(tenant_id,seller_marketplace_id,id);

CREATE TABLE finance_item_allocation_review (
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  event_id UUID NOT NULL,
  order_id UUID NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(state IN ('DRAFT','PENDING','APPROVED','REJECTED')),
  evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  version INTEGER NOT NULL DEFAULT 1 CHECK(version>0),
  requested_by UUID NOT NULL,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  approved_by UUID,
  approved_at TIMESTAMPTZ,
  PRIMARY KEY(tenant_id,event_id),
  UNIQUE(tenant_id,event_id,order_id),
  FOREIGN KEY(tenant_id,seller_marketplace_id,event_id)
    REFERENCES finance_source_event(tenant_id,seller_marketplace_id,id),
  FOREIGN KEY(tenant_id,seller_marketplace_id,order_id)
    REFERENCES sales_order(tenant_id,seller_marketplace_id,id),
  FOREIGN KEY(tenant_id,requested_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,approved_by) REFERENCES membership(tenant_id,user_id),
  CHECK(approved_by IS NULL OR approved_by<>requested_by),
  CHECK ((state IN ('DRAFT','PENDING'))=(approved_at IS NULL AND approved_by IS NULL))
);

CREATE TABLE finance_item_allocation_line (
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  order_id UUID NOT NULL,
  external_item_id VARCHAR(160) NOT NULL,
  category VARCHAR(32) NOT NULL
    CHECK(category IN ('PRODUCT_SALES','AMAZON_FEE','FBA_FEE')),
  signed_amount NUMERIC(20,6) NOT NULL CHECK(signed_amount<>0),
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  evidence_reference VARCHAR(160) NOT NULL
    CHECK(evidence_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
  PRIMARY KEY(tenant_id,event_id,external_item_id,category),
  FOREIGN KEY(tenant_id,event_id,order_id)
    REFERENCES finance_item_allocation_review(tenant_id,event_id,order_id),
  FOREIGN KEY(tenant_id,order_id,external_item_id)
    REFERENCES order_item(tenant_id,sales_order_id,external_item_id)
);

CREATE TABLE finance_item_allocation_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  version INTEGER NOT NULL CHECK(version>0),
  actor_user_id UUID NOT NULL,
  action VARCHAR(24) NOT NULL
    CHECK(action IN ('DRAFT_CREATED','LINE_SAVED','LINE_WITHDRAWN',
                     'SUBMITTED','APPROVED','REJECTED','REOPENED')),
  item_id VARCHAR(160),
  category VARCHAR(32),
  signed_amount NUMERIC(20,6),
  reason VARCHAR(500) NOT NULL CHECK(length(trim(reason)) BETWEEN 10 AND 500),
  evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,event_id)
    REFERENCES finance_item_allocation_review(tenant_id,event_id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_item_allocation_audit_recent_v60
  ON finance_item_allocation_audit(tenant_id,event_id,occurred_at DESC,id);
CREATE INDEX finance_item_allocation_order_v60
  ON finance_item_allocation_review(tenant_id,order_id,state);
