-- V47: receiving-stage lot partitions and internal QC-hold / storage locations.
-- Location metadata is separate from signed warehouse stock; no bin action posts
-- inventory, creates a physical receipt, or changes Amazon availability.
CREATE TABLE erp_local_stock_bin (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  bin_code VARCHAR(64) NOT NULL CHECK(bin_code ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
  purpose VARCHAR(16) NOT NULL CHECK(purpose IN ('QC_HOLD','STORAGE')),
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ARCHIVED')),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,warehouse_id,id),
  UNIQUE(tenant_id,warehouse_id,bin_code),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id)
);

-- Each tenant warehouse gets an INTERNAL virtual QC staging bin. This does not
-- prove that a physical shelf/slot exists until locally confirmed.
INSERT INTO erp_local_stock_bin
  (id,tenant_id,warehouse_id,bin_code,purpose,created_by)
SELECT gen_random_uuid(),w.tenant_id,w.id,'QC-HOLD','QC_HOLD',w.created_by
FROM erp_local_warehouse w;

CREATE TABLE erp_receipt_lot_piece (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  receipt_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  bin_id UUID NOT NULL,
  piece_code VARCHAR(80) NOT NULL
    CHECK(piece_code ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{1,79}$'),
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,receipt_id,piece_code),
  FOREIGN KEY(tenant_id,receipt_id)
    REFERENCES erp_receipt_quality_batch(tenant_id,receipt_id),
  FOREIGN KEY(tenant_id,warehouse_id,bin_id)
    REFERENCES erp_local_stock_bin(tenant_id,warehouse_id,id),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_receipt_lot_piece_bin_idx
  ON erp_receipt_lot_piece(tenant_id,warehouse_id,bin_id,product_sku_id);

-- Backfill only V46 batches which are STILL PENDING_QC: historic inspected or
-- released receipts retain their original evidence, not fabricated locations.
INSERT INTO erp_receipt_lot_piece
  (id,tenant_id,receipt_id,warehouse_id,product_sku_id,
   bin_id,piece_code,quantity,created_by)
SELECT gen_random_uuid(),b.tenant_id,b.receipt_id,b.warehouse_id,
  b.product_sku_id,z.id,'ROOT',b.received_quantity,b.received_by
FROM erp_receipt_quality_batch b
JOIN erp_local_stock_bin z
  ON z.tenant_id=b.tenant_id AND z.warehouse_id=b.warehouse_id
  AND z.bin_code='QC-HOLD' AND z.purpose='QC_HOLD'
WHERE b.status='PENDING_QC';

CREATE TABLE erp_receipt_lot_piece_event (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  receipt_id UUID NOT NULL,
  piece_id UUID NOT NULL,
  related_piece_id UUID,
  request_key UUID,
  actor_user_id UUID NOT NULL,
  event_type VARCHAR(24) NOT NULL
    CHECK(event_type IN ('RECEIVED','SPLIT','MOVED')),
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
  old_bin_id UUID,
  new_bin_id UUID,
  note VARCHAR(300),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,request_key),
  FOREIGN KEY(tenant_id,receipt_id)
    REFERENCES erp_receipt_quality_batch(tenant_id,receipt_id),
  FOREIGN KEY(tenant_id,piece_id) REFERENCES erp_receipt_lot_piece(tenant_id,id),
  FOREIGN KEY(tenant_id,related_piece_id) REFERENCES erp_receipt_lot_piece(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_receipt_lot_piece_event_recent
  ON erp_receipt_lot_piece_event(tenant_id,receipt_id,created_at DESC,id DESC);
