-- V37: human-confirmed replenishment -> LOCAL purchase DRAFT, with immutable evidence snapshot.
-- Never edits Amazon inventory, creates shipments, or approves a purchase order.
CREATE TABLE erp_replenishment_purchase_draft_source (
  tenant_id UUID NOT NULL,
  request_key UUID NOT NULL,
  purchase_order_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  seller_sku VARCHAR(200) NOT NULL,
  product_sku_id UUID NOT NULL,
  supplier_id UUID NOT NULL,
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  unit_cost NUMERIC(20,6) NOT NULL CHECK(unit_cost>=0),
  policy_updated_at TIMESTAMPTZ NOT NULL,
  observation_id UUID NOT NULL,
  observed_at TIMESTAMPTZ NOT NULL,
  fulfillable INTEGER NOT NULL CHECK(fulfillable>=0),
  reorder_point INTEGER NOT NULL CHECK(reorder_point>=0),
  target_stock INTEGER NOT NULL CHECK(target_stock>reorder_point),
  lead_time_days INTEGER NOT NULL CHECK(lead_time_days BETWEEN 1 AND 365),
  suggested_quantity INTEGER NOT NULL CHECK(suggested_quantity BETWEEN 1 AND 1000000),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,request_key),
  UNIQUE(tenant_id,purchase_order_id),
  CHECK(fulfillable<=reorder_point AND suggested_quantity=target_stock-fulfillable),
  FOREIGN KEY(tenant_id,purchase_order_id)
    REFERENCES erp_purchase_order(tenant_id,id),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id)
    REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,supplier_id)
    REFERENCES erp_supplier(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_replenishment_purchase_recent
  ON erp_replenishment_purchase_draft_source
  (tenant_id,seller_marketplace_id,created_at DESC);
