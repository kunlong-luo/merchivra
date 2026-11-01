-- V51: independently approved corrective resolution of V50 exceptions.
-- Surplus binds ONLY to already released/unlocated stock; never creates a receipt.
-- Destination shortages decrement previously posted stock exactly once.
CREATE TABLE erp_local_lot_exception_resolution (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  resolution_type VARCHAR(24) NOT NULL
    CHECK(resolution_type IN ('RELEASED_SURPLUS_BIND','TRANSFER_SHORTAGE')),
  count_review_id UUID,
  transfer_action_id UUID,
  source_receipt_id UUID,
  position_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  expected_bin_id UUID NOT NULL,
  expected_position_quantity INTEGER NOT NULL CHECK(expected_position_quantity>=0),
  expected_on_hand BIGINT NOT NULL CHECK(expected_on_hand>=0),
  adjustment_quantity INTEGER NOT NULL CHECK(adjustment_quantity BETWEEN 1 AND 1000000),
  evidence_reference VARCHAR(120) NOT NULL
    CHECK(length(btrim(evidence_reference)) BETWEEN 4 AND 120),
  reason VARCHAR(300) NOT NULL
    CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING_REVIEW'
    CHECK(status IN ('PENDING_REVIEW','APPLIED','REJECTED')),
  proposed_by UUID NOT NULL,
  reviewed_by UUID,
  review_reason VARCHAR(300),
  reviewed_at TIMESTAMPTZ,
  operation_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  UNIQUE(tenant_id,count_review_id),
  UNIQUE(tenant_id,transfer_action_id),
  CHECK(
    (resolution_type='RELEASED_SURPLUS_BIND' AND
      count_review_id IS NOT NULL AND transfer_action_id IS NULL
      AND source_receipt_id IS NOT NULL)
    OR (resolution_type='TRANSFER_SHORTAGE' AND
      count_review_id IS NULL AND transfer_action_id IS NOT NULL
      AND source_receipt_id IS NULL)
  ),
  CHECK(
    (status='PENDING_REVIEW' AND reviewed_by IS NULL
      AND reviewed_at IS NULL AND review_reason IS NULL AND operation_id IS NULL)
    OR (status='REJECTED' AND reviewed_by IS NOT NULL
      AND reviewed_at IS NOT NULL AND review_reason IS NOT NULL AND operation_id IS NULL)
    OR (status='APPLIED' AND reviewed_by IS NOT NULL
      AND reviewed_at IS NOT NULL AND review_reason IS NOT NULL)
  ),
  CHECK(operation_id IS NULL OR resolution_type='TRANSFER_SHORTAGE'),
  FOREIGN KEY(tenant_id,count_review_id)
    REFERENCES erp_local_lot_count_review(tenant_id,id),
  FOREIGN KEY(tenant_id,transfer_action_id)
    REFERENCES erp_local_lot_action(tenant_id,id),
  FOREIGN KEY(tenant_id,source_receipt_id)
    REFERENCES erp_receipt_quality_batch(tenant_id,receipt_id),
  FOREIGN KEY(tenant_id,position_id)
    REFERENCES erp_local_lot_position(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id)
    REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,expected_bin_id)
    REFERENCES erp_local_stock_bin(tenant_id,id),
  FOREIGN KEY(tenant_id,operation_id)
    REFERENCES erp_local_stock_operation(tenant_id,id),
  FOREIGN KEY(tenant_id,proposed_by)
    REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_lot_exception_resolution_recent_idx
  ON erp_local_lot_exception_resolution(tenant_id,warehouse_id,status,created_at DESC);

-- Immutably bind a resolved surplus to preexisting QC release, or a correction
-- to the original signed V49 transfer. No second negative inventory movement
-- for a single transfer shortage and no positive movement for a surplus bind.
ALTER TABLE erp_local_lot_event
  DROP CONSTRAINT erp_local_lot_event_event_type_check;
ALTER TABLE erp_local_lot_event
  ADD CONSTRAINT erp_local_lot_event_event_type_check
  CHECK(event_type IN ('PUTAWAY','PICKED','MOVED','TRANSFER_OUT',
        'TRANSFER_IN','QUARANTINED','SURPLUS_BOUND','SHORTAGE_CORRECTED'));

-- Evidence metadata, not file storage or third-party verification. One
-- approved/rejected internal metadata review for each V50 completed report.
CREATE TABLE erp_local_lot_disposition_proof (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  disposition_id UUID NOT NULL,
  proof_kind VARCHAR(32) NOT NULL
    CHECK(proof_kind IN ('DISPOSAL_CERTIFICATE','SUPPLIER_RETURN_RECEIPT')),
  evidence_reference VARCHAR(120) NOT NULL
    CHECK(length(btrim(evidence_reference)) BETWEEN 4 AND 120),
  document_sha256 CHAR(64)
    CHECK(document_sha256 ~ '^[a-f0-9]{64}$'),
  explanation VARCHAR(300) NOT NULL
    CHECK(length(btrim(explanation)) BETWEEN 5 AND 300),
  submitted_by UUID NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW'
    CHECK(status IN ('PENDING_REVIEW','METADATA_REVIEWED','REJECTED')),
  reviewed_by UUID,
  review_reason VARCHAR(300),
  reviewed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  UNIQUE(tenant_id,disposition_id),
  CHECK((status='PENDING_REVIEW' AND reviewed_by IS NULL
      AND review_reason IS NULL AND reviewed_at IS NULL)
      OR (status<>'PENDING_REVIEW' AND reviewed_by IS NOT NULL
        AND review_reason IS NOT NULL AND reviewed_at IS NOT NULL)),
  FOREIGN KEY(tenant_id,disposition_id)
    REFERENCES erp_local_lot_disposition(tenant_id,id),
  FOREIGN KEY(tenant_id,submitted_by)
    REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_local_lot_disposition_proof_review_idx
  ON erp_local_lot_disposition_proof(tenant_id,status,created_at DESC);
