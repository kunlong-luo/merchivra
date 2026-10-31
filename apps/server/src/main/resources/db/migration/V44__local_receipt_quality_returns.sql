-- V44 local warehouse receipt QC. Original receipts always stay immutable.
-- Rejected units leave the recorded on-hand balance once, into a separate QC hold.
CREATE TABLE erp_purchase_receipt_qc (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  request_key UUID NOT NULL,
  purchase_receipt_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  product_sku_id UUID NOT NULL,
  inspected_quantity INTEGER NOT NULL CHECK(inspected_quantity BETWEEN 1 AND 1000000),
  accepted_quantity INTEGER NOT NULL CHECK(accepted_quantity>=0),
  rejected_quantity INTEGER NOT NULL CHECK(rejected_quantity>=0),
  reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  inspected_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  UNIQUE(tenant_id,purchase_receipt_id),
  CHECK(accepted_quantity+rejected_quantity=inspected_quantity),
  FOREIGN KEY(tenant_id,purchase_receipt_id)
    REFERENCES erp_purchase_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,product_sku_id)
    REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,inspected_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_purchase_receipt_qc_recent
  ON erp_purchase_receipt_qc(tenant_id,created_at DESC,id DESC);

-- Reuse the signed stock movement audit trail; never turn rejected units into
-- sales-ready warehouse stock and never double-decrement them on supplier return.
ALTER TABLE erp_local_stock_movement
  DROP CONSTRAINT erp_local_stock_movement_source_check;
ALTER TABLE erp_local_stock_movement
  ADD COLUMN inspection_id UUID,
  ADD CONSTRAINT erp_local_stock_movement_inspection_fk
    FOREIGN KEY(tenant_id,inspection_id)
      REFERENCES erp_purchase_receipt_qc(tenant_id,id),
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
  ),
  ADD CONSTRAINT erp_local_stock_movement_one_qc
    UNIQUE(tenant_id,inspection_id);

CREATE TABLE erp_supplier_return (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  request_key UUID NOT NULL,
  inspection_id UUID NOT NULL,
  supplier_id UUID NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
  reference VARCHAR(120),
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','DISPATCHED','CANCELLED')),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  dispatched_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  CHECK((status='DISPATCHED' AND dispatched_at IS NOT NULL)
    OR (status='DRAFT' AND dispatched_at IS NULL)
    OR status='CANCELLED'),
  FOREIGN KEY(tenant_id,inspection_id)
    REFERENCES erp_purchase_receipt_qc(tenant_id,id),
  FOREIGN KEY(tenant_id,supplier_id)
    REFERENCES erp_supplier(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_supplier_return_qc
  ON erp_supplier_return(tenant_id,inspection_id,created_at DESC);

CREATE TABLE erp_purchase_quality_event (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  inspection_id UUID NOT NULL,
  return_id UUID,
  event_type VARCHAR(24) NOT NULL
    CHECK(event_type IN ('INSPECTED','RETURN_DRAFTED','RETURN_DISPATCHED','RETURN_CANCELLED')),
  actor_user_id UUID NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity>=0),
  note VARCHAR(300),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,inspection_id)
    REFERENCES erp_purchase_receipt_qc(tenant_id,id),
  FOREIGN KEY(tenant_id,return_id)
    REFERENCES erp_supplier_return(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_purchase_quality_event_recent
  ON erp_purchase_quality_event(tenant_id,inspection_id,created_at DESC);

CREATE TABLE erp_purchase_quality_finding (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  inspection_id UUID NOT NULL,
  rule_code VARCHAR(32) NOT NULL
    CHECK(rule_code IN ('QC_REJECTED_PENDING','QC_HOLD_AGED')),
  state VARCHAR(20) NOT NULL DEFAULT 'OPEN'
    CHECK(state IN ('OPEN','ACKNOWLEDGED','DONE','CLEARED')),
  last_scan_id UUID NOT NULL,
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,inspection_id,rule_code),
  FOREIGN KEY(tenant_id,inspection_id)
    REFERENCES erp_purchase_receipt_qc(tenant_id,id)
);
CREATE TABLE erp_purchase_quality_finding_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  finding_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  previous_state VARCHAR(20) NOT NULL,
  new_state VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,finding_id)
    REFERENCES erp_purchase_quality_finding(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id)
    REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE erp_purchase_quality_scan_run (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  actor_user_id UUID,
  trigger_type VARCHAR(16) NOT NULL CHECK(trigger_type IN ('MANUAL','SCHEDULED')),
  matched_count INTEGER NOT NULL CHECK(matched_count>=0),
  cleared_count INTEGER NOT NULL CHECK(cleared_count>=0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,actor_user_id)
    REFERENCES membership(tenant_id,user_id)
);
