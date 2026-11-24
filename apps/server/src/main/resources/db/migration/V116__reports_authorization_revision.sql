-- V116: non-reused local revision identities, not external certification.
CREATE SEQUENCE spapi_report_auth_revision_v116 AS BIGINT NO CYCLE;
ALTER TABLE spapi_seller_authorization
  ADD COLUMN report_auth_revision BIGINT NOT NULL DEFAULT nextval('spapi_report_auth_revision_v116')
    CHECK (report_auth_revision>0);
ALTER TABLE spapi_finances_role_evidence
  ADD COLUMN report_auth_revision BIGINT NOT NULL DEFAULT nextval('spapi_report_auth_revision_v116')
    CHECK (report_auth_revision>0);
CREATE FUNCTION spapi_next_report_auth_revision_v116() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.report_auth_revision := nextval('spapi_report_auth_revision_v116');
  RETURN NEW;
END;
$$;
CREATE TRIGGER spapi_grant_report_revision_v116
  BEFORE INSERT OR UPDATE ON spapi_seller_authorization
  FOR EACH ROW EXECUTE FUNCTION spapi_next_report_auth_revision_v116();
CREATE TRIGGER spapi_role_report_revision_v116
  BEFORE INSERT OR UPDATE ON spapi_finances_role_evidence
  FOR EACH ROW EXECUTE FUNCTION spapi_next_report_auth_revision_v116();
ALTER TABLE spapi_settlement_scan_job
  ADD COLUMN report_grant_revision BIGINT,
  ADD COLUMN report_role_revision BIGINT,
  ADD CONSTRAINT settlement_scan_revision_pair_v116 CHECK (
    (report_grant_revision IS NULL AND report_role_revision IS NULL) OR
    (report_grant_revision IS NOT NULL AND report_role_revision IS NOT NULL
     AND report_grant_revision>0 AND report_role_revision>0));
ALTER TABLE spapi_settlement_report
  ADD COLUMN report_grant_revision BIGINT,
  ADD COLUMN report_role_revision BIGINT,
  ADD CONSTRAINT settlement_report_revision_pair_v116 CHECK (
    (report_grant_revision IS NULL AND report_role_revision IS NULL) OR
    (report_grant_revision IS NOT NULL AND report_role_revision IS NOT NULL
     AND report_grant_revision>0 AND report_role_revision>0));
CREATE FUNCTION spapi_freeze_report_auth_revision_v116() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.report_grant_revision IS DISTINCT FROM OLD.report_grant_revision
    OR NEW.report_role_revision IS DISTINCT FROM OLD.report_role_revision THEN
    RAISE EXCEPTION 'Immutable report authorization revision';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER spapi_report_revision_immutable_v116
  BEFORE UPDATE OF report_grant_revision,report_role_revision ON spapi_settlement_report
  FOR EACH ROW EXECUTE FUNCTION spapi_freeze_report_auth_revision_v116();
