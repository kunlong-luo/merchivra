-- V207 / V110: original custody document governance after V109 content checks.
-- Never infer independent issuer truth from an internal retention review.
-- No release of legal hold, no document DELETE, no purge/scheduler, no COGS.
-- Reuse tenant/Owner/CSRF, append-only evidence and V109 original integrity.

CREATE TABLE finance_custody_original_retention_proposal_v110 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 file_id UUID NOT NULL,
 proposed_by UUID NOT NULL,
 retention_until TIMESTAMPTZ NOT NULL,
 reason VARCHAR(500) NOT NULL
   CHECK(length(btrim(reason)) BETWEEN 10 AND 500),
 proposed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 state VARCHAR(30) NOT NULL DEFAULT 'PROPOSED_ONLY'
   CHECK(state='PROPOSED_ONLY'),
 legal_hold BOOLEAN NOT NULL DEFAULT TRUE CHECK(legal_hold=TRUE),
 deletion_permitted BOOLEAN NOT NULL DEFAULT FALSE CHECK(deletion_permitted=FALSE),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,file_id),
 FOREIGN KEY(tenant_id,file_id)
   REFERENCES finance_dispatch_custody_original_v109(tenant_id,id),
 FOREIGN KEY(tenant_id,proposed_by) REFERENCES membership(tenant_id,user_id)
);

CREATE TABLE finance_custody_original_retention_review_v110 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 proposal_id UUID NOT NULL,
 reviewed_by UUID NOT NULL,
 decision VARCHAR(32) NOT NULL
   CHECK(decision IN ('REVIEWED_NO_PURGE','REJECTED')),
 reason VARCHAR(500) NOT NULL
   CHECK(length(btrim(reason)) BETWEEN 10 AND 500),
 reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 state VARCHAR(30) NOT NULL DEFAULT 'INTERNAL_REVIEW_ONLY'
   CHECK(state='INTERNAL_REVIEW_ONLY'),
 legal_hold BOOLEAN NOT NULL DEFAULT TRUE CHECK(legal_hold=TRUE),
 deletion_permitted BOOLEAN NOT NULL DEFAULT FALSE CHECK(deletion_permitted=FALSE),
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(operator_identity_verified=FALSE),
 platform_fulfillment_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(platform_fulfillment_verified=FALSE),
 actual_batch_cogs_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(actual_batch_cogs_verified=FALSE),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,proposal_id),
 FOREIGN KEY(tenant_id,proposal_id)
   REFERENCES finance_custody_original_retention_proposal_v110(tenant_id,id),
 FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id)
);

CREATE TABLE finance_custody_original_metadata_access_v110 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 file_id UUID NOT NULL,
 accessed_by UUID NOT NULL,
 access_purpose VARCHAR(32) NOT NULL
   CHECK(access_purpose='PRIVATE_METADATA_REVIEW'),
 snapshot_sha256 CHAR(64) NOT NULL CHECK(snapshot_sha256 ~ '^[a-f0-9]{64}$'),
 snapshot_size_bytes INTEGER NOT NULL CHECK(snapshot_size_bytes BETWEEN 5 AND 2097152),
 accessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 original_bytes_returned BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(original_bytes_returned=FALSE),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,file_id)
   REFERENCES finance_dispatch_custody_original_v109(tenant_id,id),
 FOREIGN KEY(tenant_id,accessed_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_custody_metadata_access_v110_idx
 ON finance_custody_original_metadata_access_v110
 (tenant_id,file_id,accessed_at DESC,id DESC);
CREATE INDEX finance_custody_retention_proposal_v110_idx
 ON finance_custody_original_retention_proposal_v110
 (tenant_id,proposed_at DESC,id DESC);

CREATE FUNCTION finance_custody_retention_proposal_guard_v110()
RETURNS trigger AS $$
DECLARE scoped RECORD;
BEGIN
 SELECT o.uploaded_by,o.dossier_id,v.original_state
 INTO scoped
 FROM finance_dispatch_custody_original_v109 o
 JOIN finance_dispatch_custody_original_integrity_v109 v
   ON v.tenant_id=o.tenant_id AND v.dossier_id=o.dossier_id
 WHERE o.tenant_id=NEW.tenant_id AND o.id=NEW.file_id
 FOR UPDATE OF o;
 IF NOT FOUND OR scoped.uploaded_by IS NULL
   OR scoped.original_state IS DISTINCT FROM 'ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED'
   OR NEW.proposed_by=scoped.uploaded_by
   OR NEW.retention_until<=now()+INTERVAL '1 day'
   OR NEW.retention_until>now()+INTERVAL '100 years' THEN
   RAISE EXCEPTION 'V110: current matched originals, independent proposer and future retention required'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_custody_retention_proposal_guard_v110
 BEFORE INSERT ON finance_custody_original_retention_proposal_v110
 FOR EACH ROW EXECUTE FUNCTION finance_custody_retention_proposal_guard_v110();

CREATE FUNCTION finance_custody_retention_review_guard_v110()
RETURNS trigger AS $$
DECLARE scoped RECORD;
BEGIN
 SELECT p.proposed_by,p.retention_until,o.uploaded_by,o.dossier_id,
        v.original_state
 INTO scoped
 FROM finance_custody_original_retention_proposal_v110 p
 JOIN finance_dispatch_custody_original_v109 o
   ON o.tenant_id=p.tenant_id AND o.id=p.file_id
 JOIN finance_dispatch_custody_original_integrity_v109 v
   ON v.tenant_id=o.tenant_id AND v.dossier_id=o.dossier_id
 WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.proposal_id
 FOR UPDATE OF p;
 IF NOT FOUND OR NEW.reviewed_by IN (scoped.proposed_by,scoped.uploaded_by)
   OR (NEW.decision='REVIEWED_NO_PURGE' AND (
       scoped.original_state IS DISTINCT FROM 'ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED'
       OR scoped.retention_until<=now())) THEN
   RAISE EXCEPTION 'V110: independent reviewer and current evidence required'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_custody_retention_review_guard_v110
 BEFORE INSERT ON finance_custody_original_retention_review_v110
 FOR EACH ROW EXECUTE FUNCTION finance_custody_retention_review_guard_v110();

-- Database-captured SHA and byte count are always from the ORIGINAL, not
-- arbitrary client-provided values; never store file contents or filenames.
CREATE FUNCTION finance_custody_metadata_access_guard_v110()
RETURNS trigger AS $$
DECLARE current_file RECORD;
BEGIN
 SELECT o.sha256,o.size_bytes INTO current_file
 FROM finance_dispatch_custody_original_v109 o
 WHERE o.tenant_id=NEW.tenant_id AND o.id=NEW.file_id;
 IF NOT FOUND THEN
   RAISE EXCEPTION 'V110: scoped private original required' USING ERRCODE='23514';
 END IF;
 NEW.snapshot_sha256 := current_file.sha256;
 NEW.snapshot_size_bytes := current_file.size_bytes;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_custody_metadata_access_guard_v110
 BEFORE INSERT ON finance_custody_original_metadata_access_v110
 FOR EACH ROW EXECUTE FUNCTION finance_custody_metadata_access_guard_v110();

-- Every governance and metadata access event is append-only. Original bytes
-- remain immutable under V109, irrespective of retention review.
CREATE TRIGGER finance_custody_retention_proposal_immutable_v110
 BEFORE UPDATE OR DELETE ON finance_custody_original_retention_proposal_v110
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();
CREATE TRIGGER finance_custody_retention_review_immutable_v110
 BEFORE UPDATE OR DELETE ON finance_custody_original_retention_review_v110
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();
CREATE TRIGGER finance_custody_metadata_access_immutable_v110
 BEFORE UPDATE OR DELETE ON finance_custody_original_metadata_access_v110
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();

CREATE VIEW finance_custody_original_governance_v110 AS
SELECT o.tenant_id,o.id AS file_id,o.dossier_id,o.document_kind,
 o.size_bytes,o.sha256,o.uploaded_by,o.uploaded_at,
 v.original_state,
 p.id AS proposal_id,p.proposed_by,p.retention_until,p.proposed_at,
 r.id AS review_id,r.reviewed_by,r.decision,r.reviewed_at,
 CASE
  WHEN v.original_state IS DISTINCT FROM 'ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED'
    THEN 'EVIDENCE_STALE_OR_UNVERIFIED'
  WHEN r.decision='REVIEWED_NO_PURGE'
    AND p.retention_until>now() THEN 'RETENTION_REVIEWED_HOLD_REMAINS'
  WHEN r.decision='REJECTED' THEN 'RETENTION_REJECTED_HOLD_REMAINS'
  WHEN p.id IS NOT NULL THEN 'RETENTION_PENDING_HOLD_REMAINS'
  ELSE 'DEFAULT_LEGAL_HOLD_NO_RETENTION'
 END AS governance_state,
 TRUE::boolean AS legal_hold,
 FALSE::boolean AS deletion_permitted,
 FALSE::boolean AS operator_identity_verified,
 FALSE::boolean AS physical_handoff_verified,
 FALSE::boolean AS platform_fulfillment_verified,
 FALSE::boolean AS actual_batch_cogs_verified,
 NULL::numeric AS realized_batch_cogs
FROM finance_dispatch_custody_original_v109 o
JOIN finance_dispatch_custody_original_integrity_v109 v
 ON v.tenant_id=o.tenant_id AND v.dossier_id=o.dossier_id
LEFT JOIN finance_custody_original_retention_proposal_v110 p
 ON p.tenant_id=o.tenant_id AND p.file_id=o.id
LEFT JOIN finance_custody_original_retention_review_v110 r
 ON r.tenant_id=p.tenant_id AND r.proposal_id=p.id;

COMMENT ON VIEW finance_custody_original_governance_v110 IS
 'Retained original always on legal hold; internal retention review never permits deletion, claims external issuer authenticity or books COGS.';
