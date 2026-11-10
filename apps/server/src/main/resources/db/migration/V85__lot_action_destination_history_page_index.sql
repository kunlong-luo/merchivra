-- V132: V49 full position history now pages both source AND destination actions.
-- Source-side history already uses erp_local_lot_action_source_recent_idx (V49).
-- This companion index avoids relying on a tenant-wide scan for destination
-- transfer evidence when a position is the receiving end of a transfer.
-- No data rewriting, no fabricated batch ancestry and no stock mutation.
CREATE INDEX erp_local_lot_action_destination_recent_idx
  ON erp_local_lot_action(tenant_id,destination_position_id,created_at DESC,id DESC)
  WHERE destination_position_id IS NOT NULL;
