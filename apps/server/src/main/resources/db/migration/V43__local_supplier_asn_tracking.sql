-- V43: explicit supplier-reported LOCAL advance shipment notices, not Amazon FBA shipments.
-- Historic purchase receipts remain independent; NULL asn_id does NOT count as ASN receipt.
CREATE TABLE erp_purchase_asn (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  request_key UUID NOT NULL,
  purchase_order_id UUID NOT NULL,
  purchase_order_line_id UUID NOT NULL,
  warehouse_id UUID NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
  expected_arrival_date DATE NOT NULL,
  tracking_reference VARCHAR(120),
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','SHIPPED','CANCELLED')),
  dispatch_reported_at TIMESTAMPTZ,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  UNIQUE(tenant_id,purchase_order_id,purchase_order_line_id,id),
  CHECK((status='SHIPPED' AND dispatch_reported_at IS NOT NULL)
    OR (status='DRAFT' AND dispatch_reported_at IS NULL)
    OR status='CANCELLED'),
  FOREIGN KEY(tenant_id,purchase_order_id,purchase_order_line_id)
    REFERENCES erp_purchase_order_line(tenant_id,purchase_order_id,id),
  FOREIGN KEY(tenant_id,warehouse_id)
    REFERENCES erp_local_warehouse(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_purchase_asn_order_idx
  ON erp_purchase_asn(tenant_id,purchase_order_id,created_at DESC);
CREATE INDEX erp_purchase_asn_eta_idx
  ON erp_purchase_asn(tenant_id,status,expected_arrival_date);

ALTER TABLE erp_purchase_receipt ADD COLUMN asn_id UUID;
ALTER TABLE erp_purchase_receipt
  ADD CONSTRAINT erp_purchase_receipt_asn_fk
  FOREIGN KEY(tenant_id,purchase_order_id,purchase_order_line_id,asn_id)
    REFERENCES erp_purchase_asn(tenant_id,purchase_order_id,purchase_order_line_id,id);
CREATE INDEX erp_purchase_receipt_asn_idx
  ON erp_purchase_receipt(tenant_id,asn_id) WHERE asn_id IS NOT NULL;

CREATE TABLE erp_purchase_asn_event (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  asn_id UUID NOT NULL,
  event_type VARCHAR(24) NOT NULL
    CHECK(event_type IN ('CREATED','SHIPMENT_REPORTED','ETA_UPDATED','CANCELLED','RECEIPT_LINKED')),
  actor_user_id UUID NOT NULL,
  quantity INTEGER,
  old_eta DATE,
  new_eta DATE,
  note VARCHAR(300),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,asn_id) REFERENCES erp_purchase_asn(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_purchase_asn_event_recent_idx
  ON erp_purchase_asn_event(tenant_id,asn_id,created_at DESC);

CREATE TABLE erp_purchase_asn_finding (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  asn_id UUID NOT NULL,
  rule_code VARCHAR(32) NOT NULL
    CHECK(rule_code IN ('SUPPLIER_ASN_OVERDUE','SUPPLIER_ASN_DUE_SOON')),
  state VARCHAR(20) NOT NULL DEFAULT 'OPEN'
    CHECK(state IN ('OPEN','ACKNOWLEDGED','DONE','CLEARED')),
  last_scan_id UUID NOT NULL,
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,asn_id,rule_code),
  FOREIGN KEY(tenant_id,asn_id) REFERENCES erp_purchase_asn(tenant_id,id)
);
CREATE INDEX erp_purchase_asn_finding_recent
  ON erp_purchase_asn_finding(tenant_id,state,changed_at DESC);

CREATE TABLE erp_purchase_asn_finding_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  finding_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  previous_state VARCHAR(20) NOT NULL,
  new_state VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,finding_id)
    REFERENCES erp_purchase_asn_finding(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id)
    REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE erp_purchase_asn_scan_run (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  actor_user_id UUID,
  trigger_type VARCHAR(16) NOT NULL CHECK(trigger_type IN ('MANUAL','SCHEDULED')),
  matched_count INTEGER NOT NULL CHECK(matched_count>=0),
  cleared_count INTEGER NOT NULL CHECK(cleared_count>=0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
