-- V22: advisory-only replenishment policies; no Amazon stock edits or PO side effects.
CREATE TABLE erp_replenishment_policy (
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  seller_sku VARCHAR(200) NOT NULL,
  reorder_point INTEGER NOT NULL CHECK(reorder_point>=0 AND reorder_point<=10000000),
  target_stock INTEGER NOT NULL CHECK(target_stock>reorder_point AND target_stock<=10000000),
  lead_time_days INTEGER NOT NULL CHECK(lead_time_days BETWEEN 1 AND 365),
  updated_by UUID NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,seller_marketplace_id,seller_sku),
  FOREIGN KEY(tenant_id,seller_marketplace_id,seller_sku)
    REFERENCES marketplace_listing(tenant_id,seller_marketplace_id,seller_sku),
  FOREIGN KEY(tenant_id,updated_by) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE erp_replenishment_policy_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  seller_sku VARCHAR(200) NOT NULL,
  actor_user_id UUID NOT NULL,
  action VARCHAR(24) NOT NULL CHECK(action IN ('POLICY_SET','POLICY_REMOVED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id)
);
CREATE INDEX erp_replenishment_audit_recent
  ON erp_replenishment_policy_audit(tenant_id,created_at DESC);
