-- V175-A: manually supplied QC observations and independent acknowledgements.
-- These are append-only triage records, not verified WMS identity or physical receipt.
-- Neither request nor acknowledgement posts inventory, reverses COGS, or certifies net profit.
ALTER TABLE finance_wms_return_observation_v99
 ADD CONSTRAINT finance_wms_observation_qc_scope_v100 UNIQUE
 (tenant_id,id,seller_marketplace_id,event_id,intake_id,warehouse_id);

CREATE TABLE finance_wms_qc_case_v100 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 event_id UUID NOT NULL,
 intake_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 observation_id UUID NOT NULL,
 qc_quantity INTEGER NOT NULL CHECK(qc_quantity BETWEEN 1 AND 1000000),
 qc_condition VARCHAR(24) NOT NULL CHECK(qc_condition IN
  ('SELLABLE_CLAIMED','UNSELLABLE_CLAIMED','UNKNOWN')),
 evidence_reference VARCHAR(160) NOT NULL
  CHECK(evidence_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 explanation VARCHAR(500) NOT NULL CHECK(length(btrim(explanation)) BETWEEN 10 AND 500),
 requested_by UUID NOT NULL,
 requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 identity_origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL_UNVERIFIED'
  CHECK(identity_origin='MANUAL_UNVERIFIED'),
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
  CHECK(operator_identity_verified=FALSE),
 physical_receipt_verified BOOLEAN NOT NULL DEFAULT FALSE
  CHECK(physical_receipt_verified=FALSE),
 inventory_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(inventory_posted=FALSE),
 cogs_reversed BOOLEAN NOT NULL DEFAULT FALSE CHECK(cogs_reversed=FALSE),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,observation_id),
 FOREIGN KEY(tenant_id,observation_id,seller_marketplace_id,event_id,intake_id,warehouse_id)
  REFERENCES finance_wms_return_observation_v99
   (tenant_id,id,seller_marketplace_id,event_id,intake_id,warehouse_id),
 FOREIGN KEY(tenant_id,requested_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_wms_qc_case_recent_v100
 ON finance_wms_qc_case_v100(tenant_id,seller_marketplace_id,event_id,requested_at DESC,id DESC);

CREATE TABLE finance_wms_qc_decision_v100 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 case_id UUID NOT NULL,
 decision VARCHAR(24) NOT NULL CHECK(decision IN ('ACKNOWLEDGED','REJECTED')),
 reason VARCHAR(500) NOT NULL CHECK(length(btrim(reason)) BETWEEN 10 AND 500),
 decided_by UUID NOT NULL,
 decided_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,case_id),
 FOREIGN KEY(tenant_id,case_id) REFERENCES finance_wms_qc_case_v100(tenant_id,id),
 FOREIGN KEY(tenant_id,decided_by) REFERENCES membership(tenant_id,user_id)
);

-- Enforce actor separation at the DATABASE boundary as well as in Java:
-- the reporter of signed evidence may neither file nor approve the QC review.
CREATE FUNCTION finance_wms_qc_actor_guard_v100() RETURNS trigger AS $$
DECLARE signed_importer UUID;
DECLARE qc_requester UUID;
BEGIN
 IF TG_TABLE_NAME='finance_wms_qc_case_v100' THEN
  SELECT v.recorded_by INTO signed_importer
  FROM finance_wms_return_observation_v99 v
  WHERE v.tenant_id=NEW.tenant_id AND v.id=NEW.observation_id;
  IF signed_importer IS NULL OR signed_importer=NEW.requested_by THEN
   RAISE EXCEPTION 'QC reporter must differ from signed observation importer' USING ERRCODE='23514';
  END IF;
 ELSE
  SELECT q.requested_by,v.recorded_by INTO qc_requester,signed_importer
  FROM finance_wms_qc_case_v100 q
  JOIN finance_wms_return_observation_v99 v
    ON v.tenant_id=q.tenant_id AND v.id=q.observation_id
  WHERE q.tenant_id=NEW.tenant_id AND q.id=NEW.case_id;
  IF qc_requester IS NULL OR NEW.decided_by IN (qc_requester,signed_importer) THEN
   RAISE EXCEPTION 'QC decision requires independent reviewer' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_wms_qc_case_actor_v100
 BEFORE INSERT ON finance_wms_qc_case_v100
 FOR EACH ROW EXECUTE FUNCTION finance_wms_qc_actor_guard_v100();
CREATE TRIGGER finance_wms_qc_decision_actor_v100
 BEFORE INSERT ON finance_wms_qc_decision_v100
 FOR EACH ROW EXECUTE FUNCTION finance_wms_qc_actor_guard_v100();
CREATE TRIGGER finance_wms_qc_case_immutable_v100
 BEFORE UPDATE OR DELETE ON finance_wms_qc_case_v100
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();
CREATE TRIGGER finance_wms_qc_decision_immutable_v100
 BEFORE UPDATE OR DELETE ON finance_wms_qc_decision_v100
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();
