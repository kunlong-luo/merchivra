-- V52: durable, tenant-isolated binary evidence for V51 proof metadata.
-- Files are explicitly internal documents; they are not third-party signatures.
CREATE TABLE erp_local_lot_disposition_proof_file (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  proof_id UUID NOT NULL,
  request_key UUID NOT NULL,
  media_type VARCHAR(40) NOT NULL
    CHECK(media_type IN ('application/pdf','image/png','image/jpeg')),
  bytes BYTEA NOT NULL CHECK(octet_length(bytes) BETWEEN 1 AND 2097152),
  size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 2097152),
  sha256 CHAR(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
  uploaded_by UUID NOT NULL,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,proof_id),
  UNIQUE(tenant_id,request_key),
  CHECK(size_bytes=octet_length(bytes)),
  FOREIGN KEY(tenant_id,proof_id)
    REFERENCES erp_local_lot_disposition_proof(tenant_id,id),
  FOREIGN KEY(tenant_id,uploaded_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_lot_proof_file_recent_idx
  ON erp_local_lot_disposition_proof_file(tenant_id,uploaded_at DESC,id DESC);

-- Link supplier-return disposal to the ORIGINAL real purchase order's supplier.
-- V44 erp_supplier_return concerns separately rejected QC pieces, so this
-- table MUST NOT reuse or count them as an additional return for released stock.
CREATE TABLE erp_lot_supplier_return_trace (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  disposition_id UUID NOT NULL,
  source_receipt_id UUID NOT NULL,
  supplier_id UUID NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
  carrier_reference VARCHAR(120) NOT NULL
    CHECK(length(btrim(carrier_reference)) BETWEEN 4 AND 120),
  reason VARCHAR(300) NOT NULL
    CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  status VARCHAR(24) NOT NULL DEFAULT 'DISPATCH_REPORTED'
    CHECK(status IN ('DISPATCH_REPORTED','SUPPLIER_RECEIPT_REPORTED','RECEIPT_MISMATCH')),
  dispatched_by UUID NOT NULL,
  dispatch_reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  received_quantity INTEGER,
  receipt_reference VARCHAR(120),
  receipt_note VARCHAR(300),
  received_reported_by UUID,
  received_reported_at TIMESTAMPTZ,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,disposition_id),
  UNIQUE(tenant_id,request_key),
  CHECK(
    (status='DISPATCH_REPORTED' AND received_quantity IS NULL
       AND receipt_reference IS NULL AND received_reported_by IS NULL
       AND received_reported_at IS NULL AND receipt_note IS NULL)
    OR
    (status IN ('SUPPLIER_RECEIPT_REPORTED','RECEIPT_MISMATCH')
       AND received_quantity BETWEEN 0 AND quantity
       AND receipt_reference IS NOT NULL AND receipt_note IS NOT NULL
       AND received_reported_by IS NOT NULL
       AND received_reported_at IS NOT NULL
       AND ((status='SUPPLIER_RECEIPT_REPORTED' AND received_quantity=quantity)
         OR (status='RECEIPT_MISMATCH' AND received_quantity<quantity)))
  ),
  FOREIGN KEY(tenant_id,disposition_id)
    REFERENCES erp_local_lot_disposition(tenant_id,id),
  FOREIGN KEY(tenant_id,source_receipt_id)
    REFERENCES erp_purchase_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,supplier_id)
    REFERENCES erp_supplier(tenant_id,id),
  FOREIGN KEY(tenant_id,dispatched_by)
    REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,received_reported_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_lot_supplier_return_trace_by_supplier_idx
  ON erp_lot_supplier_return_trace(tenant_id,supplier_id,status,dispatch_reported_at DESC);
