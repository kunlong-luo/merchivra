-- V138: governance metadata for V52 private original files.
-- Never modify, revoke or delete the BYTEA original. An approved disposal
-- REVIEW is not a deletion permit and there is deliberately no purge worker.
-- Existing V52 originals have NO retention policy or disposition eligibility.
CREATE TABLE erp_lot_proof_file_governance (
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  proof_id UUID NOT NULL,
  file_id UUID NOT NULL,
  legal_hold BOOLEAN NOT NULL DEFAULT false,
  hold_set_by UUID,
  hold_release_requested_by UUID,
  retention_until TIMESTAMPTZ,
  retention_proposed_until TIMESTAMPTZ,
  retention_proposed_by UUID,
  disposal_state VARCHAR(24) NOT NULL DEFAULT 'NONE'
    CHECK(disposal_state IN ('NONE','REQUESTED','REVIEWED_NO_DELETE')),
  disposal_requested_by UUID,
  disposal_reviewed_by UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,file_id),
  UNIQUE(tenant_id,proof_id),
  FOREIGN KEY(tenant_id,proof_id,file_id)
    REFERENCES erp_local_lot_disposition_proof_file(tenant_id,proof_id,id),
  FOREIGN KEY(tenant_id,hold_set_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,hold_release_requested_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,retention_proposed_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,disposal_requested_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,disposal_reviewed_by) REFERENCES membership(tenant_id,user_id),
  CHECK(NOT legal_hold OR hold_set_by IS NOT NULL),
  CHECK(legal_hold OR hold_release_requested_by IS NULL),
  CHECK((retention_proposed_until IS NULL)=(retention_proposed_by IS NULL)),
  CHECK((disposal_state='NONE' AND disposal_requested_by IS NULL AND disposal_reviewed_by IS NULL)
     OR (disposal_state='REQUESTED' AND disposal_requested_by IS NOT NULL AND disposal_reviewed_by IS NULL)
     OR (disposal_state='REVIEWED_NO_DELETE' AND disposal_requested_by IS NOT NULL AND disposal_reviewed_by IS NOT NULL)),
  CHECK(disposal_state <> 'REVIEWED_NO_DELETE' OR NOT legal_hold)
);

-- Immutable governance event journal, with no original bytes or filenames.
-- Actors are current tenant members; each transition is durably appended.
CREATE TABLE erp_lot_proof_file_governance_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  proof_id UUID NOT NULL,
  file_id UUID NOT NULL,
  action VARCHAR(32) NOT NULL CHECK(action IN (
    'PLACE_HOLD','REQUEST_HOLD_RELEASE','APPROVE_HOLD_RELEASE',
    'PROPOSE_RETENTION','APPROVE_RETENTION',
    'REQUEST_DISPOSAL','REVIEW_DISPOSAL')),
  actor_user_id UUID NOT NULL,
  reason VARCHAR(500) NOT NULL
    CHECK(length(btrim(reason)) BETWEEN 10 AND 500),
  retention_candidate TIMESTAMPTZ,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,proof_id,file_id)
    REFERENCES erp_local_lot_disposition_proof_file(tenant_id,proof_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_lot_proof_gov_audit_recent_idx
  ON erp_lot_proof_file_governance_audit
  (tenant_id,proof_id,recorded_at DESC,id DESC);

-- This forward-only Flyway migration defines no DELETE or purge capability.
-- Backup retention, litigation holds, database owner rights and encryption
-- must be reviewed on the actual target; metadata alone is not WORM storage.
