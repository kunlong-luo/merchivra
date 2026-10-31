-- V40: manual LOCAL warehouse outbound, transfer and reconciled physical counts.
-- This ledger never imports or mutates Amazon FBA/FBM quantities.
CREATE TABLE erp_local_stock_operation (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  operation_type VARCHAR(16) NOT NULL
    CHECK(operation_type IN ('OUTBOUND','TRANSFER','COUNT')),
  source_warehouse_id UUID NOT NULL,
  destination_warehouse_id UUID,
  product_sku_id UUID NOT NULL,
  requested_quantity INTEGER NOT NULL CHECK(requested_quantity BETWEEN 0 AND 10000000),
  expected_on_hand BIGINT NOT NULL CHECK(expected_on_hand>=0),
  applied_delta INTEGER NOT NULL CHECK(applied_delta<>0 AND abs(applied_delta)<=1000000),
  after_quantity BIGINT NOT NULL CHECK(after_quantity>=0),
  reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  CHECK(after_quantity=expected_on_hand+applied_delta),
  CHECK(
    (operation_type='TRANSFER' AND destination_warehouse_id IS NOT NULL
      AND source_warehouse_id<>destination_warehouse_id
      AND requested_quantity BETWEEN 1 AND 1000000
      AND applied_delta=-requested_quantity)
    OR (operation_type='OUTBOUND' AND destination_warehouse_id IS NULL
      AND requested_quantity BETWEEN 1 AND 1000000
      AND applied_delta=-requested_quantity)
    OR (operation_type='COUNT' AND destination_warehouse_id IS NULL
      AND requested_quantity=after_quantity)
  ),
  FOREIGN KEY(tenant_id,source_warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,destination_warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id)
    REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_stock_operation_recent
  ON erp_local_stock_operation(tenant_id,created_at DESC,id DESC);

-- Previously every movement was a positive purchase receipt with its own receipt ID.
-- Keep V39 receipts intact and allow exactly one source: receipt or manual operation.
ALTER TABLE erp_local_stock_movement DROP CONSTRAINT erp_local_stock_movement_movement_type_check;
ALTER TABLE erp_local_stock_movement DROP CONSTRAINT erp_local_stock_movement_quantity_check;
ALTER TABLE erp_local_stock_movement
  ALTER COLUMN purchase_receipt_id DROP NOT NULL;
ALTER TABLE erp_local_stock_movement
  ADD COLUMN operation_id UUID,
  ADD CONSTRAINT erp_local_stock_movement_operation_fk
    FOREIGN KEY(tenant_id,operation_id) REFERENCES erp_local_stock_operation(tenant_id,id),
  ADD CONSTRAINT erp_local_stock_movement_source_check CHECK(
    (movement_type='PURCHASE_RECEIPT' AND purchase_receipt_id IS NOT NULL
      AND operation_id IS NULL AND quantity>0)
    OR (movement_type IN ('OUTBOUND','TRANSFER_OUT') AND purchase_receipt_id IS NULL
      AND operation_id IS NOT NULL AND quantity<0)
    OR (movement_type IN ('TRANSFER_IN','COUNT_ADJUSTMENT')
      AND purchase_receipt_id IS NULL AND operation_id IS NOT NULL AND quantity<>0
      AND (movement_type<>'TRANSFER_IN' OR quantity>0))
  ),
  ADD CONSTRAINT erp_local_stock_movement_nonzero CHECK(quantity<>0),
  ADD CONSTRAINT erp_local_stock_movement_one_operation_type
    UNIQUE(tenant_id,operation_id,movement_type);
