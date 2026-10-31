-- V20: local purchasing operations; never changes Amazon inventory or finance.
CREATE TABLE erp_supplier (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  supplier_code VARCHAR(60) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','ARCHIVED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,supplier_code)
);
CREATE TABLE erp_purchase_order (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  supplier_id UUID NOT NULL,
  order_number VARCHAR(64) NOT NULL,
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','RECEIVING','RECEIVED','CANCELLED')),
  notes VARCHAR(300),
  created_by UUID NOT NULL,
  approved_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  approved_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_number),
  FOREIGN KEY(tenant_id,supplier_id) REFERENCES erp_supplier(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,approved_by) REFERENCES membership(tenant_id,user_id),
  CHECK(approved_by IS NULL OR approved_by <> created_by)
);
CREATE TABLE erp_purchase_order_line (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  purchase_order_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  ordered_quantity INTEGER NOT NULL CHECK(ordered_quantity>0),
  unit_cost NUMERIC(20,6) NOT NULL CHECK(unit_cost>=0),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,purchase_order_id,id),
  UNIQUE(tenant_id,purchase_order_id,product_sku_id),
  FOREIGN KEY(tenant_id,purchase_order_id) REFERENCES erp_purchase_order(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id)
);
CREATE TABLE erp_purchase_receipt (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  purchase_order_id UUID NOT NULL,
  purchase_order_line_id UUID NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity>0),
  received_by UUID NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,purchase_order_id) REFERENCES erp_purchase_order(tenant_id,id),
  FOREIGN KEY(tenant_id,purchase_order_id,purchase_order_line_id)
    REFERENCES erp_purchase_order_line(tenant_id,purchase_order_id,id),
  FOREIGN KEY(tenant_id,received_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_purchase_receipt_line_idx
  ON erp_purchase_receipt(tenant_id,purchase_order_line_id);
CREATE TABLE erp_purchase_order_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  purchase_order_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  event_type VARCHAR(24) NOT NULL CHECK(
    event_type IN ('CREATED','SUBMITTED','APPROVED','RECEIVED_PART','RECEIVED_FULL','CANCELLED')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,purchase_order_id) REFERENCES erp_purchase_order(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_purchase_order_recent ON erp_purchase_order(tenant_id,updated_at DESC,id);
