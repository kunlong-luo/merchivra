-- V173: separate signed source provenance from physical WMS verification.
-- A tenant Owner can enroll a key; a valid Ed25519 signature proves only
-- possession of THAT key, not independent warehouse operator identity.
ALTER TABLE finance_customer_return_intake_v97
 ADD CONSTRAINT finance_customer_return_intake_wh_v98
 UNIQUE(tenant_id,id,warehouse_id);

CREATE TABLE finance_wms_signing_source_v98 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 source_code VARCHAR(80) NOT NULL
   CHECK(source_code ~ '^[A-Z0-9][A-Z0-9_-]{7,79}$'),
 ed25519_spki_base64 VARCHAR(120) NOT NULL
   CHECK(ed25519_spki_base64 ~ '^[A-Za-z0-9+/]{58,116}={0,2}$'),
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
   CHECK(state IN ('PENDING','APPROVED','REVOKED')),
 identity_origin VARCHAR(24) NOT NULL DEFAULT 'TENANT_REGISTERED'
   CHECK(identity_origin='TENANT_REGISTERED'),
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(operator_identity_verified=FALSE),
 requested_by UUID NOT NULL,
 requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 decided_by UUID,
 decided_at TIMESTAMPTZ,
 decision_reason VARCHAR(500),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,warehouse_id,source_code),
 UNIQUE(tenant_id,id,warehouse_id),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES erp_local_warehouse(tenant_id,id),
 FOREIGN KEY(tenant_id,requested_by) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,decided_by) REFERENCES membership(tenant_id,user_id),
 CHECK(requested_by IS DISTINCT FROM decided_by),
 CHECK((state='PENDING' AND decided_by IS NULL AND decided_at IS NULL
  AND decision_reason IS NULL)
   OR (state<>'PENDING' AND decided_by IS NOT NULL
   AND decided_at IS NOT NULL AND decision_reason IS NOT NULL))
);
CREATE INDEX finance_wms_signing_source_recent_v98
 ON finance_wms_signing_source_v98(tenant_id,warehouse_id,requested_at DESC,id DESC);

CREATE TABLE finance_wms_signed_return_v98 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 source_id UUID NOT NULL,
 intake_id UUID NOT NULL,
 external_receipt_id VARCHAR(160) NOT NULL
   CHECK(external_receipt_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 observed_at TIMESTAMPTZ NOT NULL,
 quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
 condition_code VARCHAR(24) NOT NULL CHECK(condition_code IN
    ('SELLABLE_CLAIMED','UNSELLABLE_CLAIMED','UNKNOWN')),
 signature_base64 VARCHAR(120) NOT NULL,
 canonical_sha256 CHAR(64) NOT NULL CHECK(canonical_sha256 ~ '^[a-f0-9]{64}$'),
 state VARCHAR(28) NOT NULL DEFAULT 'SIGNATURE_VALID_UNTRUSTED'
   CHECK(state='SIGNATURE_VALID_UNTRUSTED'),
 signature_valid BOOLEAN NOT NULL DEFAULT TRUE CHECK(signature_valid=TRUE),
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(operator_identity_verified=FALSE),
 physical_receipt_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(physical_receipt_verified=FALSE),
 inventory_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(inventory_posted=FALSE),
 cogs_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(cogs_posted=FALSE),
 recorded_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,source_id,external_receipt_id),
 UNIQUE(tenant_id,intake_id),
 FOREIGN KEY(tenant_id,source_id,warehouse_id)
   REFERENCES finance_wms_signing_source_v98(tenant_id,id,warehouse_id),
 FOREIGN KEY(tenant_id,intake_id,warehouse_id)
   REFERENCES finance_customer_return_intake_v97(tenant_id,id,warehouse_id),
 FOREIGN KEY(tenant_id,recorded_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_wms_signed_return_recent_v98
 ON finance_wms_signed_return_v98(tenant_id,warehouse_id,created_at DESC,id DESC);

CREATE TABLE finance_wms_source_audit_v98 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 source_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 action VARCHAR(16) NOT NULL
   CHECK(action IN ('REGISTERED','APPROVED','REVOKED')),
 note VARCHAR(500) NOT NULL CHECK(length(btrim(note)) BETWEEN 10 AND 500),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,source_id) REFERENCES finance_wms_signing_source_v98(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_wms_source_audit_recent_v98
 ON finance_wms_source_audit_v98(tenant_id,source_id,occurred_at DESC,id DESC);

CREATE FUNCTION finance_wms_source_guard_v98() RETURNS trigger AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'WMS signing source immutable'; END IF;
 IF NOT ((OLD.state='PENDING' AND NEW.state IN ('APPROVED','REVOKED'))
      OR (OLD.state='APPROVED' AND NEW.state='REVOKED'))
   OR NEW.id IS DISTINCT FROM OLD.id
   OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
   OR NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
   OR NEW.source_code IS DISTINCT FROM OLD.source_code
   OR NEW.ed25519_spki_base64 IS DISTINCT FROM OLD.ed25519_spki_base64
   OR NEW.identity_origin IS DISTINCT FROM OLD.identity_origin
   OR NEW.operator_identity_verified IS DISTINCT FROM OLD.operator_identity_verified
   OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
   OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
   OR NEW.decided_by IS NULL OR NEW.decided_by=OLD.requested_by
   OR NEW.decided_at IS NULL OR NEW.decision_reason IS NULL THEN
   RAISE EXCEPTION 'Source key material and identity are immutable';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_wms_source_guard_v98
 BEFORE UPDATE OR DELETE ON finance_wms_signing_source_v98
 FOR EACH ROW EXECUTE FUNCTION finance_wms_source_guard_v98();

CREATE FUNCTION finance_wms_append_only_v98() RETURNS trigger AS $$
BEGIN
 RAISE EXCEPTION 'Signed WMS evidence and audit cannot be changed or deleted';
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_wms_signed_return_guard_v98
 BEFORE UPDATE OR DELETE ON finance_wms_signed_return_v98
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();
CREATE TRIGGER finance_wms_source_audit_guard_v98
 BEFORE UPDATE OR DELETE ON finance_wms_source_audit_v98
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();
