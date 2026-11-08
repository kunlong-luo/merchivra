-- V112: match the local ledger page's tenant + warehouse + timestamp/UUID order.
-- Preserves append-only movement history and does not change stock balances.
CREATE INDEX erp_local_stock_movement_warehouse_page_idx
  ON erp_local_stock_movement(tenant_id,warehouse_id,created_at DESC,id DESC);
