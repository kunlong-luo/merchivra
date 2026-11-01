-- V45: supplier discrepancy observations and human-approved claims.
-- Never change local stock, supplier payment, or Amazon FBA facts from these rows.
CREATE TABLE erp_purchase_variance (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  purchase_order_id UUID NOT NULL,
  purchase_order_line_id UUID NOT NULL,
  asn_id UUID,
  kind VARCHAR(24) NOT NULL CHECK(kind IN
    ('PO_SHORTAGE','ASN_SHORTAGE','OVERAGE_UNPOSTED')),
  expected_quantity INTEGER NOT NULL CHECK(expected_quantity BETWEEN 0 AND 1000000),
  observed_quantity INTEGER NOT NULL CHECK(observed_quantity BETWEEN 0 AND 2000000),
  variance_quantity INTEGER NOT NULL CHECK(variance_quantity BETWEEN 1 AND 1000000),
  reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  state VARCHAR(16) NOT NULL DEFAULT 'OPEN'
    CHECK(state IN ('OPEN','REVIEWED','CLOSED')),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_by UUID,
  reviewed_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  CHECK((kind='ASN_SHORTAGE' AND asn_id IS NOT NULL) OR
        (kind<>'ASN_SHORTAGE' AND asn_id IS NULL)),
  CHECK((kind IN ('PO_SHORTAGE','ASN_SHORTAGE')
           AND expected_quantity=observed_quantity+variance_quantity)
    OR (kind='OVERAGE_UNPOSTED'
           AND observed_quantity=expected_quantity+variance_quantity)),
  CHECK((state='OPEN' AND reviewed_by IS NULL AND reviewed_at IS NULL)
    OR (state IN ('REVIEWED','CLOSED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
  FOREIGN KEY(tenant_id,purchase_order_id,purchase_order_line_id)
    REFERENCES erp_purchase_order_line(tenant_id,purchase_order_id,id),
  FOREIGN KEY(tenant_id,purchase_order_id,purchase_order_line_id,asn_id)
    REFERENCES erp_purchase_asn(tenant_id,purchase_order_id,purchase_order_line_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id)
);
-- NULLS NOT DISTINCT avoids a second PO shortage/overage report
-- against the same line without falsely assigning it to a particular ASN.
CREATE UNIQUE INDEX erp_purchase_variance_one_source
  ON erp_purchase_variance(tenant_id,purchase_order_line_id,kind,asn_id)
  NULLS NOT DISTINCT;
CREATE INDEX erp_purchase_variance_order_idx
  ON erp_purchase_variance(tenant_id,purchase_order_id,created_at DESC);

CREATE TABLE erp_supplier_claim (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  request_key UUID NOT NULL,
  variance_id UUID,
  inspection_id UUID,
  purchase_order_id UUID NOT NULL,
  supplier_id UUID NOT NULL,
  requested_quantity INTEGER NOT NULL CHECK(requested_quantity BETWEEN 1 AND 1000000),
  remedy VARCHAR(20) NOT NULL CHECK(remedy IN ('REPLACEMENT','CREDIT_REQUEST')),
  reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 5 AND 300),
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','CLOSED','CANCELLED')),
  created_by UUID NOT NULL,
  approved_by UUID,
  approval_at TIMESTAMPTZ,
  resolution_note VARCHAR(300),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  CHECK((variance_id IS NOT NULL AND inspection_id IS NULL)
     OR (variance_id IS NULL AND inspection_id IS NOT NULL)),
  CHECK(approved_by IS NULL OR approved_by<>created_by),
  CHECK((status IN ('DRAFT','SUBMITTED','CANCELLED') AND approved_by IS NULL
         AND approval_at IS NULL)
    OR (status IN ('APPROVED','CLOSED') AND approved_by IS NOT NULL
         AND approval_at IS NOT NULL)),
  FOREIGN KEY(tenant_id,variance_id) REFERENCES erp_purchase_variance(tenant_id,id),
  FOREIGN KEY(tenant_id,inspection_id) REFERENCES erp_purchase_receipt_qc(tenant_id,id),
  FOREIGN KEY(tenant_id,purchase_order_id) REFERENCES erp_purchase_order(tenant_id,id),
  FOREIGN KEY(tenant_id,supplier_id) REFERENCES erp_supplier(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,approved_by) REFERENCES membership(tenant_id,user_id)
);
CREATE UNIQUE INDEX erp_supplier_claim_one_variance
  ON erp_supplier_claim(tenant_id,variance_id) WHERE variance_id IS NOT NULL;
CREATE UNIQUE INDEX erp_supplier_claim_one_inspection
  ON erp_supplier_claim(tenant_id,inspection_id) WHERE inspection_id IS NOT NULL;
CREATE INDEX erp_supplier_claim_order_idx
  ON erp_supplier_claim(tenant_id,purchase_order_id,created_at DESC);

CREATE TABLE erp_supplier_case_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  variance_id UUID,
  claim_id UUID,
  actor_user_id UUID NOT NULL,
  event_type VARCHAR(24) NOT NULL CHECK(event_type IN (
     'VARIANCE_REPORTED','VARIANCE_REVIEWED','VARIANCE_CLOSED',
     'CLAIM_DRAFTED','CLAIM_SUBMITTED','CLAIM_APPROVED',
     'CLAIM_CLOSED','CLAIM_CANCELLED')),
  note VARCHAR(300),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK((variance_id IS NOT NULL AND claim_id IS NULL)
    OR (variance_id IS NULL AND claim_id IS NOT NULL)),
  FOREIGN KEY(tenant_id,variance_id) REFERENCES erp_purchase_variance(tenant_id,id),
  FOREIGN KEY(tenant_id,claim_id) REFERENCES erp_supplier_claim(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_supplier_case_audit_recent
  ON erp_supplier_case_audit(tenant_id,created_at DESC,id DESC);
