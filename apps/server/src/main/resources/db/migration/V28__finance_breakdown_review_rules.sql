-- V28: owner-approved local interpretation rules, never mutate immutable source facts.
-- Rule changes take effect only in read-only previews; history is append-only.
CREATE TABLE finance_breakdown_rule (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  source_type VARCHAR(160) NOT NULL,
  category VARCHAR(32) NOT NULL CHECK(category IN
    ('PRODUCT_SALES','PRODUCT_REFUND','AMAZON_FEE','FBA_FEE','ADJUSTMENT','UNCLASSIFIED')),
  state VARCHAR(16) NOT NULL CHECK(state IN ('ACTIVE','INACTIVE')),
  revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),
  updated_by UUID NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,seller_marketplace_id,source_type),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,updated_by) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE finance_breakdown_rule_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  rule_id UUID NOT NULL,
  actor_id UUID NOT NULL,
  action VARCHAR(16) NOT NULL CHECK(action IN ('CREATED','CHANGED','DEACTIVATED')),
  previous_category VARCHAR(32),
  new_category VARCHAR(32),
  previous_state VARCHAR(16),
  new_state VARCHAR(16) NOT NULL,
  revision INTEGER NOT NULL CHECK(revision>0),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,rule_id) REFERENCES finance_breakdown_rule(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_rule_audit_recent
 ON finance_breakdown_rule_audit(tenant_id,rule_id,occurred_at DESC);
