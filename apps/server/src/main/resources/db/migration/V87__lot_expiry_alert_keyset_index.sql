-- V134: full tenant/warehouse expiry alert navigation.
-- Only physical positions with remaining_quantity>0 and a declared expiry date
-- can appear; no automatic quarantine/stock writes or retroactive expiry backfill.
CREATE INDEX erp_local_lot_position_expiry_page_idx
  ON erp_local_lot_position(tenant_id,warehouse_id,expiry_date ASC,id ASC)
  WHERE remaining_quantity>0 AND expiry_date IS NOT NULL;
