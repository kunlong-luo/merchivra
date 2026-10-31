-- V42: LOCAL supplier SKU terms and evidence-bound, human-confirmed PO planning.
-- Planning is advisory: no Amazon API call or implied supplier shipment.
CREATE TABLE erp_supplier_sku_rule (
  tenant_id UUID NOT NULL,
  supplier_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  minimum_order_quantity INTEGER NOT NULL CHECK(minimum_order_quantity BETWEEN 1 AND 1000000),
  pack_multiple INTEGER NOT NULL CHECK(pack_multiple BETWEEN 1 AND 1000000),
  lead_time_days INTEGER NOT NULL CHECK(lead_time_days BETWEEN 0 AND 365),
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  unit_cost NUMERIC(20,6) NOT NULL CHECK(unit_cost>=0),
  updated_by UUID NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id,supplier_id,product_sku_id),
  FOREIGN KEY (tenant_id,supplier_id) REFERENCES erp_supplier(tenant_id,id),
  FOREIGN KEY (tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY (tenant_id,updated_by) REFERENCES membership(tenant_id,user_id)
);

CREATE TABLE erp_supplier_sku_rule_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  supplier_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  event_type VARCHAR(16) NOT NULL CHECK(event_type IN ('RULE_SET','RULE_REMOVED')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id,supplier_id) REFERENCES erp_supplier(tenant_id,id),
  FOREIGN KEY (tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY (tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);

CREATE TABLE erp_local_purchase_plan (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  supplier_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  source_movement_id UUID NOT NULL,
  policy_updated_at TIMESTAMPTZ NOT NULL,
  rule_updated_at TIMESTAMPTZ NOT NULL,
  on_hand_quantity BIGINT NOT NULL CHECK(on_hand_quantity>=0),
  reorder_point INTEGER NOT NULL CHECK(reorder_point>=0),
  target_stock INTEGER NOT NULL CHECK(target_stock>reorder_point),
  suggested_quantity INTEGER NOT NULL CHECK(suggested_quantity BETWEEN 1 AND 1000000),
  minimum_order_quantity INTEGER NOT NULL CHECK(minimum_order_quantity BETWEEN 1 AND 1000000),
  pack_multiple INTEGER NOT NULL CHECK(pack_multiple BETWEEN 1 AND 1000000),
  lead_time_days INTEGER NOT NULL CHECK(lead_time_days BETWEEN 0 AND 365),
  ordered_quantity INTEGER NOT NULL CHECK(ordered_quantity BETWEEN 1 AND 1000000),
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  unit_cost NUMERIC(20,6) NOT NULL CHECK(unit_cost>=0),
  estimated_arrival_date DATE NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PROPOSED'
    CHECK(status IN ('PROPOSED','CONVERTED','CANCELLED')),
  purchase_order_id UUID,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  -- No second local purchase plan/PO from the same SKU/warehouse movement evidence.
  UNIQUE(tenant_id,warehouse_id,product_sku_id,source_movement_id),
  CHECK((status='CONVERTED' AND purchase_order_id IS NOT NULL)
    OR (status<>'CONVERTED' AND purchase_order_id IS NULL)),
  CHECK(ordered_quantity>=suggested_quantity
    AND ordered_quantity>=minimum_order_quantity
    AND ordered_quantity % pack_multiple=0),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,supplier_id) REFERENCES erp_supplier(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,source_movement_id) REFERENCES erp_local_stock_movement(tenant_id,id),
  FOREIGN KEY(tenant_id,purchase_order_id) REFERENCES erp_purchase_order(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id)
);
CREATE UNIQUE INDEX erp_local_purchase_plan_order_idx
  ON erp_local_purchase_plan(tenant_id,purchase_order_id)
  WHERE purchase_order_id IS NOT NULL;
CREATE INDEX erp_local_purchase_plan_recent_idx
  ON erp_local_purchase_plan(tenant_id,created_at DESC,id DESC);

CREATE TABLE erp_local_purchase_plan_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  plan_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  event_type VARCHAR(16) NOT NULL CHECK(event_type IN ('PROPOSED','CONVERTED','CANCELLED')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,plan_id) REFERENCES erp_local_purchase_plan(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
