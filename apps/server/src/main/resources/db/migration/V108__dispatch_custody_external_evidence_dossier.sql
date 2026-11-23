-- V205 / V108. Operator-registry and carrier-handoff document REFERENCES
-- for V107 signed local dispatches. The claims and SHA-256 strings are provided
-- by a tenant, NOT fetched/verified against a real external registry or carrier.
-- Even an independent tenant Owner review CANNOT prove third-party identity,
-- physical custody, Amazon FBA/FBM fulfillment, or posting-eligible actual COGS.
CREATE TABLE finance_dispatch_custody_dossier_v108 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 marketplace_id UUID NOT NULL,
 sales_order_id UUID NOT NULL,
 signed_dispatch_id UUID NOT NULL,
 snapshot_claim_id UUID NOT NULL,
 snapshot_source_id UUID NOT NULL,
 snapshot_warehouse_id UUID NOT NULL,
 snapshot_dispatch_ref VARCHAR(160) NOT NULL,
 snapshot_dispatch_sha256 CHAR(64) NOT NULL CHECK(snapshot_dispatch_sha256 ~ '^[a-f0-9]{64}$'),
 snapshot_quantity INTEGER NOT NULL CHECK(snapshot_quantity BETWEEN 1 AND 1000000),
 operator_registry_ref VARCHAR(160) NOT NULL
   CHECK(operator_registry_ref ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 operator_document_sha256 CHAR(64) NOT NULL
   CHECK(operator_document_sha256 ~ '^[0-9a-f]{64}$'),
 carrier_handoff_ref VARCHAR(160) NOT NULL
   CHECK(carrier_handoff_ref ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 carrier_document_sha256 CHAR(64) NOT NULL
   CHECK(carrier_document_sha256 ~ '^[0-9a-f]{64}$'),
 CHECK(operator_document_sha256<>carrier_document_sha256),
 claimed_handoff_at TIMESTAMPTZ NOT NULL,
 evidence_origin VARCHAR(32) NOT NULL DEFAULT 'TENANT_DECLARED_REFERENCES'
   CHECK(evidence_origin='TENANT_DECLARED_REFERENCES'),
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(operator_identity_verified=FALSE),
 physical_handoff_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(physical_handoff_verified=FALSE),
 platform_fulfillment_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(platform_fulfillment_verified=FALSE),
 actual_batch_cogs_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(actual_batch_cogs_verified=FALSE),
 cogs_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(cogs_posted=FALSE),
 submitted_by UUID NOT NULL,
 submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,signed_dispatch_id),
 FOREIGN KEY(tenant_id,marketplace_id) REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY(tenant_id,signed_dispatch_id)
   REFERENCES finance_sale_lot_signed_dispatch_v107(tenant_id,id),
 FOREIGN KEY(tenant_id,submitted_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_dispatch_custody_order_v108 ON finance_dispatch_custody_dossier_v108
 (tenant_id,marketplace_id,sales_order_id,id);
CREATE INDEX finance_dispatch_custody_operator_v108 ON finance_dispatch_custody_dossier_v108
 (tenant_id,snapshot_source_id,operator_registry_ref);
CREATE INDEX finance_dispatch_custody_carrier_v108 ON finance_dispatch_custody_dossier_v108
 (tenant_id,carrier_handoff_ref);

-- Immutable, one-time internal independent document-consistency review.
-- A positive review is intentionally called DOCS_CONSISTENT_UNVERIFIED; it is
-- NOT authentication of the named operator, document issuer or shipment.
CREATE TABLE finance_dispatch_custody_review_v108 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 dossier_id UUID NOT NULL,
 reviewed_by UUID NOT NULL,
 decision VARCHAR(40) NOT NULL
   CHECK(decision IN ('DOCS_CONSISTENT_UNVERIFIED','NEEDS_EVIDENCE','REJECTED')),
 reason VARCHAR(500) NOT NULL CHECK(length(btrim(reason)) BETWEEN 10 AND 500),
 snapshot_operator_document_sha256 CHAR(64) NOT NULL
   CHECK(snapshot_operator_document_sha256 ~ '^[a-f0-9]{64}$'),
 snapshot_carrier_document_sha256 CHAR(64) NOT NULL
   CHECK(snapshot_carrier_document_sha256 ~ '^[a-f0-9]{64}$'),
 reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(operator_identity_verified=FALSE),
 physical_handoff_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(physical_handoff_verified=FALSE),
 platform_fulfillment_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(platform_fulfillment_verified=FALSE),
 cogs_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(cogs_posted=FALSE),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,dossier_id),
 FOREIGN KEY(tenant_id,dossier_id) REFERENCES finance_dispatch_custody_dossier_v108(tenant_id,id),
 FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id)
);

-- The database, not a caller, captures immutable V107 dispatch identity.
CREATE FUNCTION finance_dispatch_custody_capture_v108() RETURNS trigger AS $$
DECLARE
 signed RECORD;
 source_state VARCHAR(16);
BEGIN
 SELECT d.claim_id,d.source_id,d.warehouse_id,d.external_dispatch_ref,
        d.canonical_sha256,d.quantity,d.observed_at,
        d.marketplace_id,d.sales_order_id,v.evidence_state
 INTO signed
 FROM finance_sale_lot_signed_dispatch_v107 d
 JOIN finance_sale_lot_dispatch_integrity_v107 v
   ON v.tenant_id=d.tenant_id AND v.id=d.id
 WHERE d.tenant_id=NEW.tenant_id AND d.id=NEW.signed_dispatch_id
 FOR UPDATE OF d;
 IF NOT FOUND
   OR signed.marketplace_id IS DISTINCT FROM NEW.marketplace_id
   OR signed.sales_order_id IS DISTINCT FROM NEW.sales_order_id
   OR signed.evidence_state IS DISTINCT FROM 'TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED'
   OR NEW.claimed_handoff_at<signed.observed_at-INTERVAL '30 days'
   OR NEW.claimed_handoff_at>signed.observed_at+INTERVAL '30 days'
   OR NEW.claimed_handoff_at>now()+INTERVAL '10 minutes'
   OR NEW.claimed_handoff_at<now()-INTERVAL '366 days' THEN
   RAISE EXCEPTION 'V108: exact current signed local dispatch and plausible handoff time required'
     USING ERRCODE='23514';
 END IF;
 SELECT state INTO source_state FROM finance_wms_signing_source_v98
 WHERE tenant_id=NEW.tenant_id AND id=signed.source_id
   AND warehouse_id=signed.warehouse_id FOR UPDATE;
 IF source_state IS DISTINCT FROM 'APPROVED' THEN
   RAISE EXCEPTION 'V108: revoked or missing tenant source'
     USING ERRCODE='23514';
 END IF;
 NEW.snapshot_claim_id := signed.claim_id;
 NEW.snapshot_source_id := signed.source_id;
 NEW.snapshot_warehouse_id := signed.warehouse_id;
 NEW.snapshot_dispatch_ref := signed.external_dispatch_ref;
 NEW.snapshot_dispatch_sha256 := signed.canonical_sha256;
 NEW.snapshot_quantity := signed.quantity;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_dispatch_custody_capture_v108 BEFORE INSERT
 ON finance_dispatch_custody_dossier_v108
 FOR EACH ROW EXECUTE FUNCTION finance_dispatch_custody_capture_v108();

-- Detect two tenant-declared "original" documents with the same external
-- identity but incompatible claimed SHA-256. Shared carrier manifest across
-- several SKUs is legitimate when the claimed digest is identical.
CREATE VIEW finance_dispatch_custody_integrity_v108 AS
WITH op_conflicts AS (
 SELECT tenant_id,snapshot_source_id,operator_registry_ref,
   count(DISTINCT operator_document_sha256) AS distinct_documents
 FROM finance_dispatch_custody_dossier_v108
 GROUP BY tenant_id,snapshot_source_id,operator_registry_ref
), carrier_conflicts AS (
 SELECT tenant_id,carrier_handoff_ref,
   count(DISTINCT carrier_document_sha256) AS distinct_documents
 FROM finance_dispatch_custody_dossier_v108
 GROUP BY tenant_id,carrier_handoff_ref
)
SELECT c.id AS dossier_id,c.tenant_id,c.marketplace_id,c.sales_order_id,
 c.signed_dispatch_id,c.snapshot_claim_id,c.snapshot_source_id,
 c.snapshot_warehouse_id,c.snapshot_dispatch_ref,c.snapshot_dispatch_sha256,
 c.snapshot_quantity,c.operator_registry_ref,c.operator_document_sha256,
 c.carrier_handoff_ref,c.carrier_document_sha256,c.claimed_handoff_at,
 c.submitted_by,c.submitted_at,r.reviewed_by,r.decision,
 CASE
  WHEN d.evidence_state IS DISTINCT FROM 'TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED'
    THEN 'SIGNED_DISPATCH_STALE'
  WHEN c.snapshot_claim_id IS DISTINCT FROM d.claim_id
    OR c.snapshot_source_id IS DISTINCT FROM d.source_id
    OR c.snapshot_warehouse_id IS DISTINCT FROM d.warehouse_id
    OR c.snapshot_dispatch_ref IS DISTINCT FROM d.external_dispatch_ref
    OR c.snapshot_dispatch_sha256 IS DISTINCT FROM d.canonical_sha256
    OR c.snapshot_quantity IS DISTINCT FROM d.quantity
    OR c.marketplace_id IS DISTINCT FROM d.marketplace_id
    OR c.sales_order_id IS DISTINCT FROM d.sales_order_id
    THEN 'CUSTODY_SNAPSHOT_STALE'
  WHEN coalesce(oc.distinct_documents,0)>1 OR coalesce(cc.distinct_documents,0)>1
    THEN 'EXTERNAL_REFERENCE_DIGEST_CONFLICT'
  WHEN r.id IS NULL THEN 'EXTERNAL_EVIDENCE_UNREVIEWED'
  WHEN r.snapshot_operator_document_sha256 IS DISTINCT FROM c.operator_document_sha256
    OR r.snapshot_carrier_document_sha256 IS DISTINCT FROM c.carrier_document_sha256
    THEN 'INTERNAL_REVIEW_SNAPSHOT_STALE'
  WHEN r.decision='DOCS_CONSISTENT_UNVERIFIED'
    THEN 'INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED'
  ELSE 'INTERNAL_REVIEW_REJECTED_OR_INCOMPLETE'
 END AS integrity_state,
 FALSE::boolean AS operator_identity_verified,
 FALSE::boolean AS physical_handoff_verified,
 FALSE::boolean AS platform_fulfillment_verified,
 FALSE::boolean AS actual_batch_cogs_verified,
 NULL::numeric AS realized_batch_cogs
FROM finance_dispatch_custody_dossier_v108 c
JOIN finance_sale_lot_dispatch_integrity_v107 d
 ON d.tenant_id=c.tenant_id AND d.id=c.signed_dispatch_id
LEFT JOIN finance_dispatch_custody_review_v108 r
 ON r.tenant_id=c.tenant_id AND r.dossier_id=c.id
LEFT JOIN op_conflicts oc
 ON oc.tenant_id=c.tenant_id AND oc.snapshot_source_id=c.snapshot_source_id
 AND oc.operator_registry_ref=c.operator_registry_ref
LEFT JOIN carrier_conflicts cc
 ON cc.tenant_id=c.tenant_id AND cc.carrier_handoff_ref=c.carrier_handoff_ref;

CREATE FUNCTION finance_dispatch_custody_review_guard_v108() RETURNS trigger AS $$
DECLARE
 dossier RECORD;
 state_now VARCHAR(64);
BEGIN
 SELECT c.submitted_by,d.recorded_by,s.requested_by,
        c.operator_document_sha256,c.carrier_document_sha256
 INTO dossier
 FROM finance_dispatch_custody_dossier_v108 c
 JOIN finance_sale_lot_signed_dispatch_v107 d
   ON d.tenant_id=c.tenant_id AND d.id=c.signed_dispatch_id
 JOIN finance_wms_signing_source_v98 s
   ON s.tenant_id=d.tenant_id AND s.id=d.source_id
 WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.dossier_id
 FOR UPDATE OF c;
 IF NOT FOUND
    OR NEW.reviewed_by IN (dossier.submitted_by,dossier.recorded_by,dossier.requested_by) THEN
   RAISE EXCEPTION 'V108: independent reviewer required, not issuer, submitter or signing key registrant'
     USING ERRCODE='23514';
 END IF;
 SELECT integrity_state INTO state_now
 FROM finance_dispatch_custody_integrity_v108
 WHERE tenant_id=NEW.tenant_id AND dossier_id=NEW.dossier_id;
 IF NEW.decision='DOCS_CONSISTENT_UNVERIFIED'
   AND state_now IS DISTINCT FROM 'EXTERNAL_EVIDENCE_UNREVIEWED' THEN
   RAISE EXCEPTION 'V108: consistent review disallowed on stale or conflicting evidence'
     USING ERRCODE='23514';
 END IF;
 NEW.snapshot_operator_document_sha256 := dossier.operator_document_sha256;
 NEW.snapshot_carrier_document_sha256 := dossier.carrier_document_sha256;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_dispatch_custody_review_guard_v108 BEFORE INSERT
 ON finance_dispatch_custody_review_v108
 FOR EACH ROW EXECUTE FUNCTION finance_dispatch_custody_review_guard_v108();
CREATE TRIGGER finance_dispatch_custody_immutable_v108
 BEFORE UPDATE OR DELETE ON finance_dispatch_custody_dossier_v108
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();
CREATE TRIGGER finance_dispatch_custody_review_immutable_v108
 BEFORE UPDATE OR DELETE ON finance_dispatch_custody_review_v108
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();
COMMENT ON VIEW finance_dispatch_custody_integrity_v108 IS
 'Only internal review of tenant-declared external document references. No B2 external identity, physical handoff, Amazon fulfillment or actual COGS verification.';
