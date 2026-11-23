-- V208 / V111. Cryptographic revalidation of TWO claimed document-issuer
-- signatures over V109 private original SHA-256 and V108 signed-dispatch identity.
-- Key enrollment/review is controlled BY TENANT OWNERS. No out-of-band trust
-- anchor/certificate chain or verified legal issuer identity exists yet.
-- NEVER conflate Ed25519 validity with independent third-party attestation.
-- No Amazon/Ads/network I/O, no physical handoff, inventory or actual COGS.

CREATE TABLE finance_custody_issuer_key_v111 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 document_kind VARCHAR(24) NOT NULL
   CHECK(document_kind IN ('OPERATOR_REGISTRY','CARRIER_HANDOFF')),
 claimed_issuer_ref VARCHAR(160) NOT NULL
   CHECK(claimed_issuer_ref ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 ed25519_spki_base64 VARCHAR(120) NOT NULL
   CHECK(ed25519_spki_base64 ~ '^[A-Za-z0-9+/]{58,116}={0,2}$'),
 key_sha256 CHAR(64) GENERATED ALWAYS AS
   (encode(sha256(decode(ed25519_spki_base64,'base64')),'hex')) STORED,
 state VARCHAR(32) NOT NULL DEFAULT 'REGISTERED_UNVERIFIED'
   CHECK(state IN ('REGISTERED_UNVERIFIED','REVIEWED_UNVERIFIED','REVOKED')),
 registered_by UUID NOT NULL,
 registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 reviewed_by UUID,
 reviewed_at TIMESTAMPTZ,
 review_reason VARCHAR(500),
 revoked_by UUID,
 revoked_at TIMESTAMPTZ,
 revoke_reason VARCHAR(500),
 external_issuer_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(external_issuer_identity_verified=FALSE),
 independent_trust_anchor_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(independent_trust_anchor_verified=FALSE),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,id,document_kind),
 UNIQUE(tenant_id,document_kind,claimed_issuer_ref,ed25519_spki_base64),
 UNIQUE(tenant_id,ed25519_spki_base64),
 FOREIGN KEY(tenant_id,registered_by) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,revoked_by) REFERENCES membership(tenant_id,user_id),
 CHECK(reviewed_by IS NULL OR reviewed_by<>registered_by),
 CHECK((reviewed_by IS NULL AND reviewed_at IS NULL AND review_reason IS NULL)
   OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
     AND length(btrim(review_reason)) BETWEEN 10 AND 500)),
 CHECK((state='REGISTERED_UNVERIFIED' AND reviewed_by IS NULL
   AND revoked_by IS NULL)
   OR (state='REVIEWED_UNVERIFIED' AND reviewed_by IS NOT NULL
     AND revoked_by IS NULL)
   OR (state='REVOKED' AND revoked_by IS NOT NULL
     AND revoked_at IS NOT NULL
     AND length(btrim(revoke_reason)) BETWEEN 10 AND 500)),
 CHECK((revoked_by IS NULL AND revoked_at IS NULL AND revoke_reason IS NULL)
   OR (revoked_by IS NOT NULL AND revoked_at IS NOT NULL
     AND length(btrim(revoke_reason)) BETWEEN 10 AND 500))
);
CREATE INDEX finance_custody_issuer_key_recent_v111
 ON finance_custody_issuer_key_v111(tenant_id,document_kind,registered_at DESC,id DESC);

CREATE FUNCTION finance_custody_issuer_key_guard_v111() RETURNS trigger AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'V111 issuer signing keys cannot be deleted' USING ERRCODE='23514';
 END IF;
 IF NEW.id IS DISTINCT FROM OLD.id
  OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
  OR NEW.document_kind IS DISTINCT FROM OLD.document_kind
  OR NEW.claimed_issuer_ref IS DISTINCT FROM OLD.claimed_issuer_ref
  OR NEW.ed25519_spki_base64 IS DISTINCT FROM OLD.ed25519_spki_base64
  OR NEW.registered_by IS DISTINCT FROM OLD.registered_by
  OR NEW.registered_at IS DISTINCT FROM OLD.registered_at
  OR (OLD.reviewed_by IS NOT NULL AND
     (NEW.reviewed_by IS DISTINCT FROM OLD.reviewed_by
      OR NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at
      OR NEW.review_reason IS DISTINCT FROM OLD.review_reason))
  OR NEW.external_issuer_identity_verified IS DISTINCT FROM OLD.external_issuer_identity_verified
  OR NEW.independent_trust_anchor_verified IS DISTINCT FROM OLD.independent_trust_anchor_verified THEN
  RAISE EXCEPTION 'V111 signing issuer identity and key material are immutable'
    USING ERRCODE='23514';
 END IF;
 IF OLD.state='REGISTERED_UNVERIFIED' AND NEW.state='REVIEWED_UNVERIFIED'
   AND NEW.reviewed_by IS NOT NULL AND NEW.reviewed_by<>OLD.registered_by
   AND NEW.reviewed_at IS NOT NULL
   AND length(btrim(NEW.review_reason)) BETWEEN 10 AND 500
   AND NEW.revoked_by IS NULL AND NEW.revoked_at IS NULL
   AND NEW.revoke_reason IS NULL THEN
   RETURN NEW;
 END IF;
 IF OLD.state IN ('REGISTERED_UNVERIFIED','REVIEWED_UNVERIFIED')
   AND NEW.state='REVOKED' AND NEW.revoked_by IS NOT NULL
   AND NEW.revoked_at IS NOT NULL
   AND length(btrim(NEW.revoke_reason)) BETWEEN 10 AND 500
   AND NEW.reviewed_by IS NOT DISTINCT FROM OLD.reviewed_by
   AND NEW.reviewed_at IS NOT DISTINCT FROM OLD.reviewed_at
   AND NEW.review_reason IS NOT DISTINCT FROM OLD.review_reason THEN
   RETURN NEW;
 END IF;
 RAISE EXCEPTION 'V111 only independent review or irrevocable revocation is allowed'
   USING ERRCODE='23514';
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_custody_issuer_key_guard_v111
 BEFORE UPDATE OR DELETE ON finance_custody_issuer_key_v111
 FOR EACH ROW EXECUTE FUNCTION finance_custody_issuer_key_guard_v111();

-- A purported issuer signs a deterministic versioned document-identity
-- payload, validated by Java Ed25519, NOT by the database. PostgreSQL captures
-- exact references and rejects source/document drift; reads ALWAYS recompute
-- the detached Ed25519 signature against the registered key.
CREATE TABLE finance_custody_issuer_signature_v111 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 dossier_id UUID NOT NULL,
 original_file_id UUID NOT NULL,
 issuer_key_id UUID NOT NULL,
 document_kind VARCHAR(24) NOT NULL
   CHECK(document_kind IN ('OPERATOR_REGISTRY','CARRIER_HANDOFF')),
 snapshot_marketplace_id UUID NOT NULL,
 snapshot_sales_order_id UUID NOT NULL,
 snapshot_signed_dispatch_id UUID NOT NULL,
 snapshot_document_ref VARCHAR(160) NOT NULL,
 snapshot_file_sha256 CHAR(64) NOT NULL CHECK(snapshot_file_sha256 ~ '^[0-9a-f]{64}$'),
 snapshot_issuer_ref VARCHAR(160) NOT NULL,
 claimed_issued_at TIMESTAMPTZ NOT NULL,
 signature_base64 VARCHAR(120) NOT NULL
   CHECK(signature_base64 ~ '^[A-Za-z0-9+/]{84,88}={0,2}$'),
 canonical_sha256 CHAR(64) NOT NULL CHECK(canonical_sha256 ~ '^[0-9a-f]{64}$'),
 imported_by UUID NOT NULL,
 imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 evidence_scope VARCHAR(48) NOT NULL DEFAULT 'TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED'
   CHECK(evidence_scope='TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED'),
 external_issuer_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(external_issuer_identity_verified=FALSE),
 independent_trust_anchor_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(independent_trust_anchor_verified=FALSE),
 physical_handoff_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(physical_handoff_verified=FALSE),
 amazon_fulfillment_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(amazon_fulfillment_verified=FALSE),
 actual_batch_cogs_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(actual_batch_cogs_verified=FALSE),
 cogs_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(cogs_posted=FALSE),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,dossier_id,document_kind),
 UNIQUE(tenant_id,original_file_id),
 FOREIGN KEY(tenant_id,dossier_id) REFERENCES finance_dispatch_custody_dossier_v108(tenant_id,id),
 FOREIGN KEY(tenant_id,original_file_id) REFERENCES finance_dispatch_custody_original_v109(tenant_id,id),
 FOREIGN KEY(tenant_id,issuer_key_id,document_kind)
   REFERENCES finance_custody_issuer_key_v111(tenant_id,id,document_kind),
 FOREIGN KEY(tenant_id,imported_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_custody_issuer_signature_dossier_v111
 ON finance_custody_issuer_signature_v111(tenant_id,dossier_id,document_kind);

CREATE FUNCTION finance_custody_issuer_signature_capture_v111() RETURNS trigger AS $$
DECLARE evidence RECORD;
DECLARE issuer RECORD;
BEGIN
 SELECT d.marketplace_id,d.sales_order_id,d.signed_dispatch_id,
   d.operator_registry_ref,d.carrier_handoff_ref,d.submitted_by,
   o.document_kind,o.sha256,o.dossier_id,o.uploaded_by,v.original_state
 INTO evidence
 FROM finance_dispatch_custody_dossier_v108 d
 JOIN finance_dispatch_custody_original_v109 o
   ON o.tenant_id=d.tenant_id AND o.dossier_id=d.id
 JOIN finance_dispatch_custody_original_integrity_v109 v
   ON v.tenant_id=d.tenant_id AND v.dossier_id=d.id
 WHERE d.tenant_id=NEW.tenant_id AND d.id=NEW.dossier_id
  AND o.id=NEW.original_file_id AND o.document_kind=NEW.document_kind
 FOR UPDATE OF o;
 IF NOT FOUND
  OR evidence.original_state IS DISTINCT FROM 'ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED'
  OR NEW.claimed_issued_at>now()+INTERVAL '10 minutes'
  OR NEW.claimed_issued_at<now()-INTERVAL '10 years' THEN
   RAISE EXCEPTION 'V111 current complete V109 original and bounded claimed signing time required'
     USING ERRCODE='23514';
 END IF;
 SELECT state,document_kind,claimed_issuer_ref,registered_by
 INTO issuer
 FROM finance_custody_issuer_key_v111
 WHERE tenant_id=NEW.tenant_id AND id=NEW.issuer_key_id
   AND document_kind=NEW.document_kind FOR UPDATE;
 IF NOT FOUND OR issuer.state IS DISTINCT FROM 'REVIEWED_UNVERIFIED'
  OR NEW.imported_by IN (
       issuer.registered_by,evidence.uploaded_by,evidence.submitted_by) THEN
   RAISE EXCEPTION 'V111 independently reviewed unverified key and separate importer required'
     USING ERRCODE='23514';
 END IF;
 NEW.snapshot_marketplace_id := evidence.marketplace_id;
 NEW.snapshot_sales_order_id := evidence.sales_order_id;
 NEW.snapshot_signed_dispatch_id := evidence.signed_dispatch_id;
 NEW.snapshot_document_ref := CASE
   WHEN NEW.document_kind='OPERATOR_REGISTRY' THEN evidence.operator_registry_ref
   ELSE evidence.carrier_handoff_ref END;
 NEW.snapshot_file_sha256 := evidence.sha256;
 NEW.snapshot_issuer_ref := issuer.claimed_issuer_ref;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_custody_issuer_signature_capture_v111
 BEFORE INSERT ON finance_custody_issuer_signature_v111
 FOR EACH ROW EXECUTE FUNCTION finance_custody_issuer_signature_capture_v111();
CREATE TRIGGER finance_custody_issuer_signature_immutable_v111
 BEFORE UPDATE OR DELETE ON finance_custody_issuer_signature_v111
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();

-- View state is NOT a cryptographic verdict: Java must recalculate the exact
-- canonical UTF-8 SHA and Ed25519 bytes, even when SQL says source current.
-- Preserve invalidated observations; revoked keys never silently re-activate.
CREATE VIEW finance_custody_issuer_signature_integrity_v111 AS
SELECT a.id,a.tenant_id,a.dossier_id,a.original_file_id,a.issuer_key_id,
 a.document_kind,a.snapshot_marketplace_id,a.snapshot_sales_order_id,
 a.snapshot_signed_dispatch_id,a.snapshot_document_ref,
 a.snapshot_file_sha256,a.snapshot_issuer_ref,a.claimed_issued_at,
 a.signature_base64,a.canonical_sha256,a.imported_by,a.imported_at,
 k.ed25519_spki_base64,k.state AS issuer_key_state,
 o.sha256 AS current_original_sha256,
 d.marketplace_id AS current_marketplace_id,
 d.sales_order_id AS current_sales_order_id,
 d.signed_dispatch_id AS current_signed_dispatch_id,
 CASE WHEN a.document_kind='OPERATOR_REGISTRY'
      THEN d.operator_registry_ref ELSE d.carrier_handoff_ref END AS current_document_ref,
 g.original_state,
 CASE
  WHEN k.state<>'REVIEWED_UNVERIFIED' THEN 'ISSUER_KEY_REVOKED_OR_UNREVIEWED'
  WHEN g.original_state IS DISTINCT FROM 'ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED'
    THEN 'ORIGINALS_OR_UPSTREAM_STALE'
  WHEN a.snapshot_file_sha256 IS DISTINCT FROM o.sha256
    OR a.snapshot_marketplace_id IS DISTINCT FROM d.marketplace_id
    OR a.snapshot_sales_order_id IS DISTINCT FROM d.sales_order_id
    OR a.snapshot_signed_dispatch_id IS DISTINCT FROM d.signed_dispatch_id
    OR a.snapshot_document_ref IS DISTINCT FROM
      CASE WHEN a.document_kind='OPERATOR_REGISTRY' THEN d.operator_registry_ref
           ELSE d.carrier_handoff_ref END
    OR a.snapshot_issuer_ref IS DISTINCT FROM k.claimed_issuer_ref
    THEN 'ISSUER_SIGNATURE_IDENTITY_DRIFT'
  ELSE 'TENANT_KEY_SOURCE_CURRENT_CRYPTO_RECOMPUTE_REQUIRED'
 END AS evidence_state,
 FALSE::boolean AS external_issuer_identity_verified,
 FALSE::boolean AS independent_trust_anchor_verified,
 FALSE::boolean AS physical_handoff_verified,
 FALSE::boolean AS amazon_fulfillment_verified,
 FALSE::boolean AS actual_batch_cogs_verified,
 NULL::numeric AS realized_batch_cogs
FROM finance_custody_issuer_signature_v111 a
JOIN finance_custody_issuer_key_v111 k
 ON k.tenant_id=a.tenant_id AND k.id=a.issuer_key_id
 AND k.document_kind=a.document_kind
JOIN finance_dispatch_custody_original_v109 o
 ON o.tenant_id=a.tenant_id AND o.id=a.original_file_id
 AND o.dossier_id=a.dossier_id AND o.document_kind=a.document_kind
JOIN finance_dispatch_custody_dossier_v108 d
 ON d.tenant_id=a.tenant_id AND d.id=a.dossier_id
JOIN finance_dispatch_custody_original_integrity_v109 g
 ON g.tenant_id=a.tenant_id AND g.dossier_id=a.dossier_id;
COMMENT ON VIEW finance_custody_issuer_signature_integrity_v111 IS
 'Exact local Ed25519 source binding only, still unverified external issuer or carrier identity. Java recalculates signature; never unlocks Amazon fulfillment or COGS.';
