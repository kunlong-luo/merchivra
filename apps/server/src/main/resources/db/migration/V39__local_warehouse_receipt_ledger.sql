-- V39: explicit LOCAL warehouse receipt accounting only; NOT Amazon FBA/FBM stock.
-- Existing V20 receipts have no warehouse assignment and are intentionally excluded
-- from warehouse balances rather than silently guessed into a default warehouse.
CREATE TABLE erp_local_warehouse (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  warehouse_code VARCHAR(60) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,warehouse_code),
  FOREIGN KEY (tenant_id,created_by) REFERENCES membership(tenant_id,user_id)
);

ALTER TABLE erp_purchase_receipt
  ADD COLUMN warehouse_id UUID;
ALTER TABLE erp_purchase_receipt
  ADD CONSTRAINT erp_purchase_receipt_tenant_id_unique UNIQUE(tenant_id,id);
ALTER TABLE erp_purchase_receipt
  ADD CONSTRAINT erp_purchase_receipt_local_warehouse_fk
  FOREIGN KEY(tenant_id,warehouse_id)
  REFERENCES erp_local_warehouse(tenant_id,id);

CREATE TABLE erp_local_stock_movement (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  purchase_receipt_id UUID NOT NULL,
  movement_type VARCHAR(32) NOT NULL DEFAULT 'PURCHASE_RECEIPT'
    CHECK(movement_type='PURCHASE_RECEIPT'),
  quantity INTEGER NOT NULL CHECK(quantity>0),
  actor_user_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,purchase_receipt_id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,purchase_receipt_id) REFERENCES erp_purchase_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_stock_movement_summary_idx
  ON erp_local_stock_movement(tenant_id,warehouse_id,product_sku_id);
CREATE INDEX erp_local_stock_movement_recent_idx
  ON erp_local_stock_movement(tenant_id,created_at DESC,id DESC);
