-- V41: LOCAL warehouse stock rules and reviewable findings, never Amazon FBA alerts.
CREATE TABLE erp_local_stock_policy (
  tenant_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  reorder_point INTEGER NOT NULL CHECK(reorder_point BETWEEN 0 AND 10000000),
  target_stock INTEGER NOT NULL CHECK(target_stock BETWEEN 1 AND 10000000),
  updated_by UUID NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,warehouse_id,product_sku_id),
  CHECK(target_stock>reorder_point),
  FOREIGN KEY(tenant_id,warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id)
    REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,updated_by)
    REFERENCES membership(tenant_id,user_id)
);

CREATE TABLE erp_local_stock_policy_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  action VARCHAR(24) NOT NULL
    CHECK(action IN ('POLICY_SET','POLICY_REMOVED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id)
    REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_stock_policy_audit_recent
  ON erp_local_stock_policy_audit(tenant_id,created_at DESC);

CREATE TABLE erp_local_stock_finding (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  rule_code VARCHAR(32) NOT NULL
    CHECK(rule_code IN ('LOCAL_LOW_STOCK','LOCAL_LEDGER_NEGATIVE','LOCAL_COUNT_VARIANCE')),
  state VARCHAR(20) NOT NULL DEFAULT 'OPEN'
    CHECK(state IN ('OPEN','ACKNOWLEDGED','DONE','CLEARED')),
  last_scan_id UUID NOT NULL,
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,warehouse_id,product_sku_id,rule_code),
  FOREIGN KEY(tenant_id,warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id)
    REFERENCES product_sku(tenant_id,id)
);
CREATE INDEX erp_local_stock_finding_recent
  ON erp_local_stock_finding(tenant_id,state,changed_at DESC,id DESC);

CREATE TABLE erp_local_stock_finding_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  finding_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  old_state VARCHAR(20) NOT NULL,
  new_state VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,finding_id)
    REFERENCES erp_local_stock_finding(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id)
    REFERENCES membership(tenant_id,user_id)
);

CREATE TABLE erp_local_stock_scan_run (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  actor_user_id UUID,
  trigger_type VARCHAR(16) NOT NULL
    CHECK(trigger_type IN ('MANUAL','SCHEDULED')),
  matched_count INTEGER NOT NULL CHECK(matched_count>=0),
  cleared_count INTEGER NOT NULL CHECK(cleared_count>=0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,actor_user_id)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_stock_scan_recent
  ON erp_local_stock_scan_run(tenant_id,created_at DESC);
