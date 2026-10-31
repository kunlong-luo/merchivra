-- V19: immutable SKU cost facts with half-open effective periods, not Amazon financial data.
-- All application writes serialize on product_sku row and close prior periods.
CREATE TABLE sku_cost_version (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  unit_cost NUMERIC(20,6) NOT NULL CHECK(unit_cost>=0),
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  effective_from DATE NOT NULL,
  effective_to DATE,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,product_sku_id,effective_from),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id),
  CHECK(effective_to IS NULL OR effective_to>effective_from)
);
CREATE UNIQUE INDEX sku_cost_one_open_period
 ON sku_cost_version(tenant_id,product_sku_id) WHERE effective_to IS NULL;
CREATE INDEX sku_cost_history_idx
 ON sku_cost_version(tenant_id,product_sku_id,effective_from DESC);
