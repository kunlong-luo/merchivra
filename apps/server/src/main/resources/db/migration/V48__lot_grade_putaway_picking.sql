-- V48: graded receiving pieces plus independently released, location-bound
-- lot quantities. No retroactive reconstruction of historical warehouse stock.
CREATE TABLE erp_receipt_piece_grade (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  receipt_id UUID NOT NULL,
  piece_id UUID NOT NULL,
  request_key UUID NOT NULL,
  accepted_quantity INTEGER NOT NULL CHECK (accepted_quantity>=0),
  rejected_quantity INTEGER NOT NULL CHECK (rejected_quantity>=0),
  reason VARCHAR(300) NOT NULL CHECK (length(btrim(reason)) BETWEEN 5 AND 300),
  inspected_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,piece_id),
  UNIQUE(tenant_id,request_key),
  CHECK (accepted_quantity+rejected_quantity>0),
  FOREIGN KEY(tenant_id,receipt_id)
    REFERENCES erp_receipt_quality_batch(tenant_id,receipt_id),
  FOREIGN KEY(tenant_id,piece_id) REFERENCES erp_receipt_lot_piece(tenant_id,id),
  FOREIGN KEY(tenant_id,inspected_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_receipt_piece_grade_receipt_idx
  ON erp_receipt_piece_grade(tenant_id,receipt_id,created_at DESC);

CREATE TABLE erp_local_lot_position (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  request_key UUID NOT NULL,
  receipt_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  bin_id UUID NOT NULL,
  placed_quantity INTEGER NOT NULL CHECK(placed_quantity BETWEEN 1 AND 1000000),
  remaining_quantity INTEGER NOT NULL CHECK(remaining_quantity>=0),
  expiry_date DATE,
  reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  CHECK(remaining_quantity<=placed_quantity),
  FOREIGN KEY(tenant_id,receipt_id)
    REFERENCES erp_receipt_quality_batch(tenant_id,receipt_id),
  FOREIGN KEY(tenant_id,warehouse_id,bin_id)
    REFERENCES erp_local_stock_bin(tenant_id,warehouse_id,id),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_lot_position_pick_idx
 ON erp_local_lot_position(tenant_id,warehouse_id,product_sku_id,expiry_date,created_at)
 WHERE remaining_quantity>0;

CREATE TABLE erp_local_lot_pick (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  request_key UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  operation_id UUID NOT NULL,
  policy VARCHAR(8) NOT NULL CHECK(policy IN ('FIFO','FEFO')),
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
  expected_on_hand BIGINT NOT NULL CHECK(expected_on_hand>=0),
  reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  actor_user_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  UNIQUE(tenant_id,operation_id),
  FOREIGN KEY(tenant_id,warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,operation_id) REFERENCES erp_local_stock_operation(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);

CREATE TABLE erp_local_lot_pick_line (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  pick_id UUID NOT NULL,
  position_id UUID NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
  UNIQUE(tenant_id,pick_id,position_id),
  FOREIGN KEY(tenant_id,pick_id) REFERENCES erp_local_lot_pick(tenant_id,id),
  FOREIGN KEY(tenant_id,position_id) REFERENCES erp_local_lot_position(tenant_id,id)
);

CREATE TABLE erp_local_lot_event (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  position_id UUID NOT NULL,
  pick_id UUID,
  event_type VARCHAR(16) NOT NULL CHECK(event_type IN ('PUTAWAY','PICKED')),
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
  actor_user_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,position_id)
    REFERENCES erp_local_lot_position(tenant_id,id),
  FOREIGN KEY(tenant_id,pick_id) REFERENCES erp_local_lot_pick(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_lot_event_recent
  ON erp_local_lot_event(tenant_id,position_id,created_at DESC);
