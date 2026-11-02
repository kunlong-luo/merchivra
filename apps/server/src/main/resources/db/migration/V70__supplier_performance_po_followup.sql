-- V73 supplier performance: only local human pre-dispatch follow-up findings.
-- SHIPPED ASN ETA alerts remain in existing V43 table; no duplicate queue.
CREATE TABLE erp_purchase_followup_finding (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  purchase_order_line_id UUID NOT NULL,
  rule_code VARCHAR(32) NOT NULL CHECK (
    rule_code IN ('ASN_COVERAGE_GAP','ASN_NOT_DISPATCHED')),
  state VARCHAR(20) NOT NULL DEFAULT 'OPEN'
    CHECK (state IN ('OPEN','ACKNOWLEDGED','DONE','CLEARED')),
  last_scan_id UUID NOT NULL,
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,purchase_order_line_id,rule_code),
  FOREIGN KEY(tenant_id,purchase_order_line_id)
    REFERENCES erp_purchase_order_line(tenant_id,id)
);
CREATE INDEX erp_purchase_followup_active_v70
 ON erp_purchase_followup_finding(tenant_id,state,changed_at DESC);
CREATE TABLE erp_purchase_followup_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  finding_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  old_state VARCHAR(20) NOT NULL,
  new_state VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,finding_id)
    REFERENCES erp_purchase_followup_finding(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE erp_purchase_followup_scan (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  actor_user_id UUID,
  trigger_type VARCHAR(16) NOT NULL CHECK (
    trigger_type IN ('MANUAL','SCHEDULED')),
  matched_count INTEGER NOT NULL CHECK (matched_count>=0),
  cleared_count INTEGER NOT NULL CHECK (cleared_count>=0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
