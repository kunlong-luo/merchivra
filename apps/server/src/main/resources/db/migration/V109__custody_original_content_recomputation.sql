-- V206 / V109: private original bytes for the two V108 custody DOCUMENTS.
-- Reuse V137 ClamAV, V52 magic/size validation and SHA-256 in Java.
-- PostgreSQL independently recomputes SHA-256 of the immutable actual bytes.
-- An authenticated tenant upload is NOT independent external issuer attestation.
-- NO real WMS identity, carrier delivery, Amazon fulfillment or COGS approval.

CREATE TABLE finance_dispatch_custody_original_v109 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 dossier_id UUID NOT NULL,
 document_kind VARCHAR(24) NOT NULL
   CHECK (document_kind IN ('OPERATOR_REGISTRY','CARRIER_HANDOFF')),
 media_type VARCHAR(32) NOT NULL
   CHECK (media_type IN ('application/pdf','image/png','image/jpeg')),
 file_bytes BYTEA NOT NULL,
 size_bytes INTEGER GENERATED ALWAYS AS (octet_length(file_bytes)) STORED,
 sha256 CHAR(64) GENERATED ALWAYS AS (encode(sha256(file_bytes),'hex')) STORED,
 uploaded_by UUID NOT NULL,
 uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 -- These flags explicitly cannot be promoted by a tenant Owner or raw SQL.
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK (operator_identity_verified=FALSE),
 physical_handoff_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK (physical_handoff_verified=FALSE),
 platform_fulfillment_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK (platform_fulfillment_verified=FALSE),
 actual_batch_cogs_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK (actual_batch_cogs_verified=FALSE),
 cogs_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK (cogs_posted=FALSE),
 UNIQUE (tenant_id,id),
 UNIQUE (tenant_id,dossier_id,document_kind),
 FOREIGN KEY (tenant_id,dossier_id)
   REFERENCES finance_dispatch_custody_dossier_v108(tenant_id,id),
 FOREIGN KEY (tenant_id,uploaded_by) REFERENCES membership(tenant_id,user_id),
 CHECK (octet_length(file_bytes) BETWEEN 5 AND 2097152),
 -- A direct database write cannot bypass file-magic integrity.
 CHECK (
   (media_type='application/pdf'
     AND substring(file_bytes from 1 for 5)=decode('255044462d','hex'))
   OR
   (media_type='image/png'
     AND substring(file_bytes from 1 for 8)=decode('89504e470d0a1a0a','hex'))
   OR
   (media_type='image/jpeg'
     AND substring(file_bytes from 1 for 3)=decode('ffd8ff','hex'))
 )
);
CREATE INDEX finance_custody_original_dossier_v109
 ON finance_dispatch_custody_original_v109(tenant_id,dossier_id,document_kind);

-- The DB does not trust a submitted hash; it hashes the actual BYTEA.
-- This also applies to any direct SQL writes, including synthetic fixtures.
CREATE FUNCTION finance_dispatch_custody_original_guard_v109()
RETURNS trigger AS $$
DECLARE
 dossier RECORD;
 current_state VARCHAR(80);
 expected_digest CHAR(64);
 total_bytes BIGINT;
BEGIN
 -- Serializes the order's private evidence additions with other files of the
 -- same tenant; capacity cannot be raced by two concurrent uploads.
 PERFORM 1 FROM tenant WHERE id=NEW.tenant_id AND status='ACTIVE' FOR UPDATE;
 IF NOT FOUND THEN
   RAISE EXCEPTION 'V109: active tenant required' USING ERRCODE='23514';
 END IF;
 SELECT c.operator_document_sha256,c.carrier_document_sha256,c.id
 INTO dossier
 FROM finance_dispatch_custody_dossier_v108 c
 WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.dossier_id
 FOR UPDATE OF c;
 IF NOT FOUND THEN
   RAISE EXCEPTION 'V109: scoped V108 evidence dossier required'
     USING ERRCODE='23514';
 END IF;
 SELECT integrity_state INTO current_state
 FROM finance_dispatch_custody_integrity_v108
 WHERE tenant_id=NEW.tenant_id AND dossier_id=NEW.dossier_id;
 IF current_state IS DISTINCT FROM 'EXTERNAL_EVIDENCE_UNREVIEWED'
   AND current_state IS DISTINCT FROM 'INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED' THEN
   RAISE EXCEPTION 'V109: disputed, rejected or stale upstream dossier'
     USING ERRCODE='23514';
 END IF;
 expected_digest := CASE WHEN NEW.document_kind='OPERATOR_REGISTRY'
     THEN dossier.operator_document_sha256
     WHEN NEW.document_kind='CARRIER_HANDOFF'
     THEN dossier.carrier_document_sha256
     ELSE NULL END;
 IF expected_digest IS NULL OR
    encode(sha256(NEW.file_bytes),'hex') IS DISTINCT FROM expected_digest THEN
   RAISE EXCEPTION 'V109: original content SHA-256 differs from asserted dossier digest'
     USING ERRCODE='23514';
 END IF;
 SELECT coalesce(sum(size_bytes),0) INTO total_bytes
 FROM finance_dispatch_custody_original_v109
 WHERE tenant_id=NEW.tenant_id;
 IF total_bytes+octet_length(NEW.file_bytes)>268435456 THEN
   RAISE EXCEPTION 'V109: private custody original bytes capacity exceeded'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_dispatch_custody_original_guard_v109
 BEFORE INSERT ON finance_dispatch_custody_original_v109
 FOR EACH ROW EXECUTE FUNCTION finance_dispatch_custody_original_guard_v109();
CREATE TRIGGER finance_dispatch_custody_original_immutable_v109
 BEFORE UPDATE OR DELETE ON finance_dispatch_custody_original_v109
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();

-- The view recomputes BOTH original digests from the stored bytes at read
-- time. The generated sha256 is only a capture snapshot, not a trusted input.
CREATE VIEW finance_dispatch_custody_original_integrity_v109 AS
SELECT d.dossier_id,d.tenant_id,d.marketplace_id,d.sales_order_id,
 d.signed_dispatch_id,d.integrity_state AS dossier_state,
 op.id AS operator_file_id,op.uploaded_by AS operator_uploaded_by,
 op.size_bytes AS operator_size_bytes,op.sha256 AS operator_saved_sha256,
 ca.id AS carrier_file_id,ca.uploaded_by AS carrier_uploaded_by,
 ca.size_bytes AS carrier_size_bytes,ca.sha256 AS carrier_saved_sha256,
 CASE
  WHEN d.integrity_state NOT IN ('EXTERNAL_EVIDENCE_UNREVIEWED',
        'INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED')
    THEN 'DOSSIER_OR_SIGNED_SOURCE_STALE'
  WHEN op.id IS NULL OR ca.id IS NULL THEN 'ORIGINAL_CONTENT_MISSING'
  WHEN op.sha256 IS DISTINCT FROM encode(sha256(op.file_bytes),'hex')
    OR ca.sha256 IS DISTINCT FROM encode(sha256(ca.file_bytes),'hex')
    OR op.sha256 IS DISTINCT FROM d.operator_document_sha256
    OR ca.sha256 IS DISTINCT FROM d.carrier_document_sha256
    THEN 'ORIGINAL_CONTENT_DIGEST_STALE'
  WHEN d.integrity_state='EXTERNAL_EVIDENCE_UNREVIEWED'
    THEN 'ORIGINALS_MATCH_BUT_INTERNAL_REVIEW_MISSING'
  ELSE 'ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED'
 END AS original_state,
 FALSE::boolean AS operator_identity_verified,
 FALSE::boolean AS physical_handoff_verified,
 FALSE::boolean AS platform_fulfillment_verified,
 FALSE::boolean AS actual_batch_cogs_verified,
 NULL::numeric AS realized_batch_cogs
FROM finance_dispatch_custody_integrity_v108 d
LEFT JOIN finance_dispatch_custody_original_v109 op
 ON op.tenant_id=d.tenant_id AND op.dossier_id=d.dossier_id
 AND op.document_kind='OPERATOR_REGISTRY'
LEFT JOIN finance_dispatch_custody_original_v109 ca
 ON ca.tenant_id=d.tenant_id AND ca.dossier_id=d.dossier_id
 AND ca.document_kind='CARRIER_HANDOFF';
COMMENT ON VIEW finance_dispatch_custody_original_integrity_v109 IS
 'SHA-256 of private original bytes is independently recalculated; matching bytes and tenant declaration never certify issuer, carrier custody, Amazon or COGS.';
