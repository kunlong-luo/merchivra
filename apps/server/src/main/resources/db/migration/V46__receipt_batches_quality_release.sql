-- V46: forward-only separation of new physical receipts from sellable local stock.
-- Existing V20/V39-V45 receipts and signed ledger entries are not rewritten.
CREATE TABLE erp_receipt_quality_batch (
  tenant_id UUID NOT NULL,
  receipt_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  received_quantity INTEGER NOT NULL CHECK(received_quantity BETWEEN 1 AND 1000000),
  lot_code VARCHAR(80) NOT NULL CHECK(length(btrim(lot_code)) BETWEEN 2 AND 80),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING_QC'
    CHECK(status IN ('PENDING_QC','INSPECTED','RELEASED')),
  inspection_id UUID,
  released_by UUID,
  released_at TIMESTAMPTZ,
  received_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id,receipt_id),
  UNIQUE(tenant_id,warehouse_id,product_sku_id,lot_code),
  CHECK(
    (status='PENDING_QC' AND inspection_id IS NULL AND released_by IS NULL AND released_at IS NULL)
    OR (status='INSPECTED' AND inspection_id IS NOT NULL AND released_by IS NULL AND released_at IS NULL)
    OR (status='RELEASED' AND inspection_id IS NOT NULL AND released_by IS NOT NULL AND released_at IS NOT NULL)
  ),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES erp_purchase_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,inspection_id) REFERENCES erp_purchase_receipt_qc(tenant_id,id),
  FOREIGN KEY(tenant_id,received_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,released_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_receipt_batch_status_idx
  ON erp_receipt_quality_batch(tenant_id,warehouse_id,status,created_at DESC);

CREATE TABLE erp_receipt_quality_batch_event (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  receipt_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  event_type VARCHAR(24) NOT NULL
    CHECK(event_type IN ('RECEIVED_TO_HOLD','LOT_RELABELED','QC_RECORDED','QC_RELEASED')),
  old_lot_code VARCHAR(80),
  new_lot_code VARCHAR(80),
  quantity INTEGER CHECK(quantity IS NULL OR quantity>=0),
  note VARCHAR(300),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES erp_receipt_quality_batch(tenant_id,receipt_id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_receipt_batch_events_recent
  ON erp_receipt_quality_batch_event(tenant_id,receipt_id,created_at DESC);

-- For new V46 batches, QC accepted quantities enter warehouse usable stock ONLY on
-- independent Owner release; rejected goods stay outside available stock from receipt.
-- Preserve legacy V44 negative QC quarantine for historic, already-stocked receipts.
ALTER TABLE erp_local_stock_movement
  DROP CONSTRAINT erp_local_stock_movement_source_check;
ALTER TABLE erp_local_stock_movement
  ADD CONSTRAINT erp_local_stock_movement_source_check CHECK(
    (movement_type='PURCHASE_RECEIPT' AND purchase_receipt_id IS NOT NULL
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
      AND operation_id IS NULL AND inspection_id IS NOT NULL AND quantity>0)
  );
-- V44 UNIQUE(tenant_id,inspection_id) also protects new QC_RELEASE from duplicate posting.
