-- V140: an opt-in per-tenant advisory storage threshold, not a quota.
-- No default threshold is invented. NULL explicitly disables an alert.
-- Actual disk/TOAST/index/WAL/backups, PITR and deletion policy remain
-- outside this payload-byte-only metric and require target-ops monitoring.
CREATE TABLE erp_lot_proof_file_capacity_threshold (
  tenant_id UUID PRIMARY KEY REFERENCES tenant(id),
  warning_bytes BIGINT CHECK (
    warning_bytes IS NULL OR warning_bytes BETWEEN 1048576 AND 1099511627776),
  changed_by UUID NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id,changed_by) REFERENCES membership(tenant_id,user_id)
);

-- Immutable application journal for each Owner configuration change. Never
-- records file bytes, filenames or customer document content.
CREATE TABLE erp_lot_proof_file_capacity_threshold_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  warning_bytes BIGINT CHECK (
    warning_bytes IS NULL OR warning_bytes BETWEEN 1048576 AND 1099511627776),
  actor_user_id UUID NOT NULL,
  reason VARCHAR(500) NOT NULL
    CHECK (length(btrim(reason)) BETWEEN 10 AND 500),
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id,actor_user_id)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_lot_proof_capacity_threshold_audit_recent_idx
  ON erp_lot_proof_file_capacity_threshold_audit
    (tenant_id,recorded_at DESC,id DESC);

-- V88's indexed (tenant_id,proof_id,recorded_at DESC,id DESC) audit keyset
-- is already adequate. Do not duplicate its B-tree or scan BYTEA contents.
-- There are deliberately no DELETE statements, jobs or expiration triggers.
