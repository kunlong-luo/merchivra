-- V174: cryptographically signed external OBSERVATIONS, not proof of real
-- warehouse identity, goods receipt, saleability, inventory or COGS.
-- Global tenant+warehouse+external receipt and tenant+intake uniqueness
-- apply ACROSS old V173 exact-match evidence and new V174 quarantine.
ALTER TABLE finance_customer_return_intake_v97
 ADD CONSTRAINT finance_customer_return_intake_event_scoped_v99
 UNIQUE(tenant_id,id,seller_marketplace_id,event_id,warehouse_id);

CREATE TABLE finance_wms_receipt_identity_v99 (
 tenant_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 external_receipt_id VARCHAR(160) NOT NULL
  CHECK (external_receipt_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 source_id UUID NOT NULL,
 intake_id UUID NOT NULL,
 evidence_kind VARCHAR(8) NOT NULL CHECK (evidence_kind IN ('V173','V174')),
 evidence_id UUID NOT NULL,
 PRIMARY KEY (tenant_id,warehouse_id,external_receipt_id),
 UNIQUE (tenant_id,intake_id),
 UNIQUE (tenant_id,evidence_kind,evidence_id),
 FOREIGN KEY (tenant_id,source_id,warehouse_id)
  REFERENCES finance_wms_signing_source_v98(tenant_id,id,warehouse_id),
 FOREIGN KEY (tenant_id,intake_id,warehouse_id)
  REFERENCES finance_customer_return_intake_v97(tenant_id,id,warehouse_id)
);
-- Existing accepted V173 signed receipts also occupy the global identity.
INSERT INTO finance_wms_receipt_identity_v99
 (tenant_id,warehouse_id,external_receipt_id,source_id,intake_id,
  evidence_kind,evidence_id)
SELECT tenant_id,warehouse_id,external_receipt_id,source_id,intake_id,'V173',id
FROM finance_wms_signed_return_v98;

CREATE TABLE finance_wms_return_observation_v99 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 event_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 source_id UUID NOT NULL,
 intake_id UUID NOT NULL,
 external_receipt_id VARCHAR(160) NOT NULL
  CHECK(external_receipt_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 observed_at TIMESTAMPTZ NOT NULL,
 external_quantity INTEGER NOT NULL CHECK(external_quantity BETWEEN 1 AND 1000000),
 external_condition VARCHAR(24) NOT NULL CHECK(external_condition IN
  ('SELLABLE_CLAIMED','UNSELLABLE_CLAIMED','UNKNOWN')),
 local_quantity INTEGER NOT NULL CHECK(local_quantity BETWEEN 1 AND 1000000),
 local_condition VARCHAR(24) NOT NULL CHECK(local_condition IN
  ('SELLABLE_CLAIMED','UNSELLABLE_CLAIMED','UNKNOWN')),
 reconciliation VARCHAR(32) NOT NULL
  CHECK(reconciliation IN ('MATCHED_UNVERIFIED','QUANTITY_MISMATCH',
      'CONDITION_MISMATCH','BOTH_MISMATCH')),
 signature_base64 VARCHAR(120) NOT NULL
  CHECK(signature_base64 ~ '^[A-Za-z0-9+/]{85,88}={0,2}$'),
 canonical_sha256 CHAR(64) NOT NULL CHECK(canonical_sha256 ~ '^[a-f0-9]{64}$'),
 identity_origin VARCHAR(24) NOT NULL DEFAULT 'TENANT_REGISTERED'
  CHECK(identity_origin='TENANT_REGISTERED'),
 signature_valid BOOLEAN NOT NULL DEFAULT TRUE CHECK(signature_valid=TRUE),
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
  CHECK(operator_identity_verified=FALSE),
 physical_receipt_verified BOOLEAN NOT NULL DEFAULT FALSE
  CHECK(physical_receipt_verified=FALSE),
 inventory_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(inventory_posted=FALSE),
 cogs_reversed BOOLEAN NOT NULL DEFAULT FALSE CHECK(cogs_reversed=FALSE),
 recorded_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (tenant_id,id),
 FOREIGN KEY (tenant_id,source_id,warehouse_id)
  REFERENCES finance_wms_signing_source_v98(tenant_id,id,warehouse_id),
 FOREIGN KEY (tenant_id,intake_id,warehouse_id)
  REFERENCES finance_customer_return_intake_v97(tenant_id,id,warehouse_id),
 FOREIGN KEY (tenant_id,intake_id,seller_marketplace_id,event_id,warehouse_id)
  REFERENCES finance_customer_return_intake_v97
   (tenant_id,id,seller_marketplace_id,event_id,warehouse_id),
 FOREIGN KEY (tenant_id,seller_marketplace_id,event_id)
  REFERENCES finance_source_event(tenant_id,seller_marketplace_id,id),
 FOREIGN KEY (tenant_id,recorded_by) REFERENCES membership(tenant_id,user_id),
 CHECK((reconciliation='MATCHED_UNVERIFIED'
        AND external_quantity=local_quantity
        AND external_condition=local_condition)
   OR (reconciliation='QUANTITY_MISMATCH'
        AND external_quantity<>local_quantity
        AND external_condition=local_condition)
   OR (reconciliation='CONDITION_MISMATCH'
        AND external_quantity=local_quantity
        AND external_condition<>local_condition)
   OR (reconciliation='BOTH_MISMATCH'
        AND external_quantity<>local_quantity
        AND external_condition<>local_condition))
);
CREATE INDEX finance_wms_return_observation_event_v99
 ON finance_wms_return_observation_v99
 (tenant_id,seller_marketplace_id,event_id,created_at DESC,id DESC);

-- Transactional global receipt reservation also closes old V173 endpoint:
-- all accepted receipts share the same unique identity, even after key rotation.
CREATE FUNCTION finance_wms_receipt_register_v99() RETURNS trigger AS $$
BEGIN
 INSERT INTO finance_wms_receipt_identity_v99
  (tenant_id,warehouse_id,external_receipt_id,source_id,intake_id,
   evidence_kind,evidence_id)
 VALUES(NEW.tenant_id,NEW.warehouse_id,NEW.external_receipt_id,
        NEW.source_id,NEW.intake_id,
        CASE WHEN TG_TABLE_NAME='finance_wms_signed_return_v98'
          THEN 'V173' ELSE 'V174' END,NEW.id);
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_wms_v173_global_id_v99
 AFTER INSERT ON finance_wms_signed_return_v98
 FOR EACH ROW EXECUTE FUNCTION finance_wms_receipt_register_v99();
CREATE TRIGGER finance_wms_v174_global_id_v99
 AFTER INSERT ON finance_wms_return_observation_v99
 FOR EACH ROW EXECUTE FUNCTION finance_wms_receipt_register_v99();

CREATE FUNCTION finance_wms_observation_immutable_v99() RETURNS trigger AS $$
BEGIN
 RAISE EXCEPTION 'Signed WMS observation and receipt identity cannot be rewritten';
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_wms_observation_guard_v99
 BEFORE UPDATE OR DELETE ON finance_wms_return_observation_v99
 FOR EACH ROW EXECUTE FUNCTION finance_wms_observation_immutable_v99();
CREATE TRIGGER finance_wms_receipt_identity_guard_v99
 BEFORE UPDATE OR DELETE ON finance_wms_receipt_identity_v99
 FOR EACH ROW EXECUTE FUNCTION finance_wms_observation_immutable_v99();
