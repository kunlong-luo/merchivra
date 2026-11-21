-- V133: stable tenant+warehouse created_at/id keyset over all original
-- and transfer-descendant released-lot positions, including consumed positions.
-- V48 pick index is SKU/expiry-oriented and cannot replace this history index.
CREATE INDEX erp_local_lot_position_warehouse_recent_idx
  ON erp_local_lot_position(tenant_id,warehouse_id,created_at DESC,id DESC);
-- Exact V48 receipt allocation is computed by tenant/receipt and root-only
-- origin_position_id IS NULL. Existing origin FK/index and receipt relationship
-- remain unchanged; no stock movement or legacy position is rewritten.
CREATE INDEX erp_local_lot_position_receipt_root_idx
  ON erp_local_lot_position(tenant_id,receipt_id)
  WHERE origin_position_id IS NULL;
