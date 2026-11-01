-- V49: source-traceable released-lot storage moves, transfers, manual expiry holds,
-- and nonposting physical count observations. Forward-only; legacy stock unchanged.
ALTER TABLE erp_local_lot_position
  ADD COLUMN origin_position_id UUID,
  ADD CONSTRAINT erp_local_lot_position_origin_fk
    FOREIGN KEY(tenant_id,origin_position_id)
    REFERENCES erp_local_lot_position(tenant_id,id);
CREATE INDEX erp_local_lot_position_origin_idx
  ON erp_local_lot_position(tenant_id,origin_position_id)
  WHERE origin_position_id IS NOT NULL;

CREATE TABLE erp_local_lot_action (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  action_type VARCHAR(22) NOT NULL
    CHECK(action_type IN ('MOVE','TRANSFER','EXPIRED_QUARANTINE','COUNT_OBSERVATION')),
  source_position_id UUID NOT NULL,
  source_warehouse_id UUID NOT NULL,
  destination_warehouse_id UUID,
  source_bin_id UUID NOT NULL,
  destination_bin_id UUID,
  destination_position_id UUID,
  operation_id UUID,
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 0 AND 1000000),
  expected_remaining INTEGER NOT NULL CHECK(expected_remaining>=0),
  expected_on_hand BIGINT,
  observed_quantity INTEGER,
  reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  actor_user_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  CHECK(
    (action_type='MOVE' AND quantity=0 AND operation_id IS NULL
       AND destination_position_id IS NULL AND destination_warehouse_id IS NULL
       AND destination_bin_id IS NOT NULL AND observed_quantity IS NULL
       AND expected_on_hand IS NULL)
    OR (action_type='TRANSFER' AND quantity>0 AND operation_id IS NOT NULL
       AND destination_position_id IS NOT NULL AND destination_warehouse_id IS NOT NULL
       AND destination_bin_id IS NOT NULL AND observed_quantity IS NULL
       AND expected_on_hand IS NOT NULL AND
       destination_warehouse_id<>source_warehouse_id)
    OR (action_type='EXPIRED_QUARANTINE' AND quantity>0
       AND operation_id IS NULL AND destination_position_id IS NULL
       AND destination_bin_id IS NULL AND destination_warehouse_id IS NULL
       AND observed_quantity IS NULL AND expected_on_hand IS NOT NULL)
    OR (action_type='COUNT_OBSERVATION' AND quantity=0
       AND operation_id IS NULL AND destination_position_id IS NULL
       AND destination_bin_id IS NULL AND destination_warehouse_id IS NULL
       AND observed_quantity>=0 AND expected_on_hand IS NULL)
  ),
  FOREIGN KEY(tenant_id,source_position_id)
    REFERENCES erp_local_lot_position(tenant_id,id),
  FOREIGN KEY(tenant_id,source_warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,source_warehouse_id,source_bin_id)
    REFERENCES erp_local_stock_bin(tenant_id,warehouse_id,id),
  FOREIGN KEY(tenant_id,destination_warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,destination_position_id)
    REFERENCES erp_local_lot_position(tenant_id,id),
  FOREIGN KEY(tenant_id,operation_id)
    REFERENCES erp_local_stock_operation(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_lot_action_source_recent_idx
  ON erp_local_lot_action(tenant_id,source_position_id,created_at DESC,id DESC);

-- A real expired-lot quarantine must decrease global usable signed stock exactly
-- once, independently of the V40 OUTBOUND movement used for true warehouse transfers.
ALTER TABLE erp_local_stock_movement
  ADD COLUMN lot_action_id UUID,
  ADD CONSTRAINT erp_local_stock_movement_lot_action_fk
    FOREIGN KEY(tenant_id,lot_action_id)
    REFERENCES erp_local_lot_action(tenant_id,id),
  ADD CONSTRAINT erp_local_stock_movement_unique_lot_action
    UNIQUE(tenant_id,lot_action_id);
ALTER TABLE erp_local_stock_movement
  DROP CONSTRAINT erp_local_stock_movement_source_check;
ALTER TABLE erp_local_stock_movement
  ADD CONSTRAINT erp_local_stock_movement_source_check CHECK(
    (lot_action_id IS NULL AND
       ((movement_type='PURCHASE_RECEIPT' AND purchase_receipt_id IS NOT NULL
         AND operation_id IS NULL AND inspection_id IS NULL AND quantity>0)
       OR (movement_type IN ('OUTBOUND','TRANSFER_OUT')
         AND purchase_receipt_id IS NULL AND operation_id IS NOT NULL
         AND inspection_id IS NULL AND quantity<0)
       OR (movement_type IN ('TRANSFER_IN','COUNT_ADJUSTMENT')
         AND purchase_receipt_id IS NULL AND operation_id IS NOT NULL
         AND inspection_id IS NULL AND quantity<>0
         AND (movement_type<>'TRANSFER_IN' OR quantity>0))
       OR (movement_type='QC_QUARANTINE' AND purchase_receipt_id IS NULL
         AND operation_id IS NULL AND inspection_id IS NOT NULL AND quantity<0)
       OR (movement_type='QC_RELEASE' AND purchase_receipt_id IS NULL
         AND operation_id IS NULL AND inspection_id IS NOT NULL AND quantity>0)))
    OR (movement_type='LOT_QUARANTINE' AND quantity<0
         AND lot_action_id IS NOT NULL
         AND purchase_receipt_id IS NULL AND operation_id IS NULL
         AND inspection_id IS NULL)
  );
