-- V175-A: execute the ACTUAL installed actor/immutability trigger functions
-- against disposable pg_temp shadow fixtures. No real merchant, refund, WMS
-- credentials or financial records are created. Transaction rolls back.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE finance_wms_return_observation_v99 (
 tenant_id uuid NOT NULL, id uuid NOT NULL, recorded_by uuid NOT NULL
);
CREATE TEMP TABLE finance_wms_qc_case_v100 (
 tenant_id uuid NOT NULL, id uuid NOT NULL, observation_id uuid NOT NULL,
 requested_by uuid NOT NULL,
 UNIQUE (tenant_id,observation_id)
);
CREATE TEMP TABLE finance_wms_qc_decision_v100 (
 tenant_id uuid NOT NULL, case_id uuid NOT NULL, decided_by uuid NOT NULL,
 UNIQUE (tenant_id,case_id)
);
CREATE TRIGGER qc_test_case_actor
 BEFORE INSERT ON finance_wms_qc_case_v100
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_qc_actor_guard_v100();
CREATE TRIGGER qc_test_decision_actor
 BEFORE INSERT ON finance_wms_qc_decision_v100
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_qc_actor_guard_v100();
CREATE TRIGGER qc_test_case_immutable
 BEFORE UPDATE OR DELETE ON finance_wms_qc_case_v100
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_append_only_v98();
CREATE TRIGGER qc_test_decision_immutable
 BEFORE UPDATE OR DELETE ON finance_wms_qc_decision_v100
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_append_only_v98();

DO $checks$
DECLARE
 tenant uuid:='00000000-0000-4000-8000-000000000175';
 imported uuid:='00000000-0000-4000-8000-000000000001';
 reporter uuid:='00000000-0000-4000-8000-000000000002';
 reviewer uuid:='00000000-0000-4000-8000-000000000003';
 obs uuid:='00000000-0000-4000-8000-000000000174';
 qc uuid:='00000000-0000-4000-8000-000000000176';
BEGIN
 INSERT INTO pg_temp.finance_wms_return_observation_v99
  (tenant_id,id,recorded_by) VALUES(tenant,obs,imported);
 -- No one may submit QC without a matching signed source identity.
 BEGIN
  INSERT INTO pg_temp.finance_wms_qc_case_v100 VALUES
   (tenant,gen_random_uuid(),gen_random_uuid(),reporter);
  RAISE EXCEPTION 'INVALID: phantom observation accepted';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 -- Signing importer cannot file the QC observation themselves.
 BEGIN
  INSERT INTO pg_temp.finance_wms_qc_case_v100 VALUES
   (tenant,qc,obs,imported);
  RAISE EXCEPTION 'INVALID: signing importer self-QC accepted';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 -- Different reporter succeeds: this is NOT WMS physical verification.
 INSERT INTO pg_temp.finance_wms_qc_case_v100 VALUES
  (tenant,qc,obs,reporter);
 -- Exact same signed observation cannot acquire another QC case.
 BEGIN
  INSERT INTO pg_temp.finance_wms_qc_case_v100 VALUES
   (tenant,gen_random_uuid(),obs,reporter);
  RAISE EXCEPTION 'INVALID: duplicate QC observation accepted';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;
 -- Importer and QC submitter cannot review their own material.
 BEGIN
  INSERT INTO pg_temp.finance_wms_qc_decision_v100 VALUES
   (tenant,qc,imported);
  RAISE EXCEPTION 'INVALID: signing importer self-review accepted';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 BEGIN
  INSERT INTO pg_temp.finance_wms_qc_decision_v100 VALUES
   (tenant,qc,reporter);
  RAISE EXCEPTION 'INVALID: QC reporter self-review accepted';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 BEGIN
  INSERT INTO pg_temp.finance_wms_qc_decision_v100 VALUES
   (tenant,gen_random_uuid(),reviewer);
  RAISE EXCEPTION 'INVALID: missing QC case accepted';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 -- Third, independent owner is allowed to record the review decision.
 INSERT INTO pg_temp.finance_wms_qc_decision_v100 VALUES
  (tenant,qc,reviewer);
 -- Decisions are one-shot; no second reviewer can overwrite.
 BEGIN
  INSERT INTO pg_temp.finance_wms_qc_decision_v100 VALUES
   (tenant,qc,gen_random_uuid());
  RAISE EXCEPTION 'INVALID: duplicate decision accepted';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;
 -- Immutable append-only triggers reject both updates and deletes.
 BEGIN
  UPDATE pg_temp.finance_wms_qc_case_v100 SET requested_by=imported
   WHERE id=qc;
  RAISE EXCEPTION 'INVALID: QC case UPDATE accepted';
 EXCEPTION WHEN raise_exception THEN
  IF SQLERRM='INVALID: QC case UPDATE accepted' THEN RAISE; END IF;
 END;
 BEGIN
  DELETE FROM pg_temp.finance_wms_qc_decision_v100 WHERE case_id=qc;
  RAISE EXCEPTION 'INVALID: QC decision DELETE accepted';
 EXCEPTION WHEN raise_exception THEN
  IF SQLERRM='INVALID: QC decision DELETE accepted' THEN RAISE; END IF;
 END;
 IF (SELECT count(*) FROM pg_temp.finance_wms_qc_case_v100)!=1 OR
    (SELECT count(*) FROM pg_temp.finance_wms_qc_decision_v100)!=1 THEN
  RAISE EXCEPTION 'V175 QC shadow fixture history was altered';
 END IF;
 RAISE NOTICE 'PASS V175 PG18 real actor/immutability trigger functions; real warehouse still unverified';
END;
$checks$;
ROLLBACK;
