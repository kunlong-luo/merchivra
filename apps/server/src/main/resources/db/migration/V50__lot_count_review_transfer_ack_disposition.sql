-- V50: independent review of counted lot shortages, transfer destination
-- acknowledgements and quarantine disposition plans. No Amazon writes.
CREATE TABLE erp_local_lot_count_review (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  observation_action_id UUID NOT NULL,
  position_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  expected_bin_id UUID NOT NULL,
  expected_position_quantity INTEGER NOT NULL CHECK(expected_position_quantity>=0),
  observed_quantity INTEGER NOT NULL CHECK(observed_quantity BETWEEN 0 AND 1000000),
  expected_on_hand BIGINT NOT NULL CHECK(expected_on_hand>=0),
  reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW'
    CHECK(status IN ('PENDING_REVIEW','APPLIED','CONFIRMED_NO_CHANGE',
                     'EVIDENCE_REQUIRED','REJECTED')),
  submitted_by UUID NOT NULL,
  reviewed_by UUID,
  reviewed_at TIMESTAMPTZ,
  review_reason VARCHAR(300),
  operation_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  UNIQUE(tenant_id,observation_action_id),
  CHECK((status='PENDING_REVIEW' AND reviewed_by IS NULL
           AND reviewed_at IS NULL AND review_reason IS NULL AND operation_id IS NULL)
        OR (status<>'PENDING_REVIEW' AND reviewed_by IS NOT NULL
           AND reviewed_at IS NOT NULL AND review_reason IS NOT NULL)),
  CHECK(operation_id IS NULL OR status='APPLIED'),
  FOREIGN KEY(tenant_id,observation_action_id) REFERENCES erp_local_lot_action(tenant_id,id),
  FOREIGN KEY(tenant_id,position_id) REFERENCES erp_local_lot_position(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id) REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,expected_bin_id) REFERENCES erp_local_stock_bin(tenant_id,id),
  FOREIGN KEY(tenant_id,operation_id) REFERENCES erp_local_stock_operation(tenant_id,id),
  FOREIGN KEY(tenant_id,submitted_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_lot_count_review_pending_idx
  ON erp_local_lot_count_review(tenant_id,warehouse_id,status,created_at DESC);

-- V49 transfer already moved ledger stock to destination. Acknowledgement
-- records a second person's physical receiving report; it MUST NOT credit again.
CREATE TABLE erp_local_lot_transfer_ack (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  transfer_action_id UUID NOT NULL,
  destination_warehouse_id UUID NOT NULL,
  expected_quantity INTEGER NOT NULL CHECK(expected_quantity BETWEEN 1 AND 1000000),
  received_quantity INTEGER NOT NULL CHECK(received_quantity BETWEEN 0 AND 1000000),
  status VARCHAR(16) NOT NULL CHECK(status IN ('CONFIRMED','MISMATCH')),
  reference VARCHAR(120) NOT NULL CHECK(length(btrim(reference)) BETWEEN 4 AND 120),
  reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  acknowledged_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),UNIQUE(tenant_id,request_key),
  UNIQUE(tenant_id,transfer_action_id),
  CHECK(received_quantity<=expected_quantity),
  CHECK((status='CONFIRMED' AND received_quantity=expected_quantity)
      OR (status='MISMATCH' AND received_quantity<expected_quantity)),
  FOREIGN KEY(tenant_id,transfer_action_id) REFERENCES erp_local_lot_action(tenant_id,id),
  FOREIGN KEY(tenant_id,destination_warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,acknowledged_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_lot_transfer_ack_recent_idx
  ON erp_local_lot_transfer_ack(tenant_id,destination_warehouse_id,created_at DESC);

-- Already isolated V49 stock is outside sellable inventory. Neither approving
-- nor reporting disposal/return may create a SECOND negative movement.
CREATE TABLE erp_local_lot_disposition (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  quarantine_action_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
  method VARCHAR(24) NOT NULL CHECK(method IN ('DISPOSAL','SUPPLIER_RETURN')),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPROVAL'
    CHECK(status IN ('PENDING_APPROVAL','APPROVED','REJECTED','EXECUTED_REPORTED')),
  reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  proposed_by UUID NOT NULL,
  reviewed_by UUID,
  review_reason VARCHAR(300),
  reviewed_at TIMESTAMPTZ,
  completed_by UUID,
  completion_reference VARCHAR(120),
  completion_reason VARCHAR(300),
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),UNIQUE(tenant_id,request_key),
  UNIQUE(tenant_id,quarantine_action_id),
  CHECK((status='PENDING_APPROVAL' AND reviewed_by IS NULL AND reviewed_at IS NULL
          AND review_reason IS NULL AND completed_by IS NULL)
       OR (status<>'PENDING_APPROVAL' AND reviewed_by IS NOT NULL AND
           reviewed_at IS NOT NULL AND review_reason IS NOT NULL)),
  CHECK((status='EXECUTED_REPORTED' AND completed_by IS NOT NULL
          AND completed_at IS NOT NULL AND completion_reference IS NOT NULL
          AND completion_reason IS NOT NULL)
        OR (status<>'EXECUTED_REPORTED' AND completed_by IS NULL
          AND completed_at IS NULL AND completion_reference IS NULL
          AND completion_reason IS NULL)),
  FOREIGN KEY(tenant_id,quarantine_action_id)
    REFERENCES erp_local_lot_action(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,proposed_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,completed_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_lot_disposition_review_idx
 ON erp_local_lot_disposition(tenant_id,warehouse_id,status,created_at DESC);
