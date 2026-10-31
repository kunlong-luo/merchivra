-- V10 · tenant-scoped operations automation: advisory tasks ONLY, never Amazon writes.
-- Duplicate detection is per (tenant, marketplace, rule, source), never cross-tenant.
CREATE TABLE ops_finding (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  seller_marketplace_id UUID NOT NULL,
  rule_code VARCHAR(40) NOT NULL
    CHECK (rule_code IN ('UNMAPPED_LISTING','INVENTORY_UNKNOWN','LOW_STOCK','REFUND_REVIEW')),
  source_key VARCHAR(256) NOT NULL,
  title VARCHAR(100) NOT NULL,
  severity VARCHAR(12) NOT NULL CHECK (severity IN ('INFO','WARNING','CRITICAL')),
  state VARCHAR(20) NOT NULL DEFAULT 'OPEN'
    CHECK (state IN ('OPEN','ACKNOWLEDGED','DONE','CLEARED')),
  data_origin VARCHAR(16) NOT NULL CHECK (data_origin IN ('MOCK','UNVERIFIED')),
  last_scan_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, seller_marketplace_id, rule_code, source_key),
  FOREIGN KEY (tenant_id, seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id, id)
);
CREATE INDEX ops_finding_queue_idx ON ops_finding
  (tenant_id, state, changed_at DESC, id DESC);
CREATE TABLE ops_automation_run (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  actor_user_id UUID,
  trigger_type VARCHAR(16) NOT NULL CHECK (trigger_type IN ('MANUAL','SCHEDULED')),
  matched_count INTEGER NOT NULL CHECK (matched_count >= 0),
  cleared_count INTEGER NOT NULL CHECK (cleared_count >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, actor_user_id) REFERENCES membership(tenant_id, user_id)
);
CREATE INDEX ops_automation_run_recent_idx
  ON ops_automation_run (tenant_id, created_at DESC, id DESC);
CREATE TABLE ops_finding_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  finding_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  old_state VARCHAR(20) NOT NULL,
  new_state VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, finding_id) REFERENCES ops_finding(tenant_id, id),
  FOREIGN KEY (tenant_id, actor_user_id) REFERENCES membership(tenant_id, user_id)
);
CREATE INDEX ops_finding_audit_recent_idx
  ON ops_finding_audit (tenant_id, finding_id, created_at DESC);
