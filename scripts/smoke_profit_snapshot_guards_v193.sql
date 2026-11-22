-- V193 isolated PG18 proof: installed V103 triggers, not fake Java mocks.
-- Only temporary V62-shaped tables are written. No production/base data,
-- no Amazon/Ads authorization, no GL/COGS posting.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path=pg_temp,public;
DO $verify$
DECLARE installed integer;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.flyway_schema_history
                 WHERE version='103' AND success) THEN
    RAISE EXCEPTION 'V103 Flyway migration missing';
  END IF;
  SELECT count(*) INTO installed
  FROM pg_catalog.pg_trigger t
  JOIN pg_catalog.pg_class c ON c.oid=t.tgrelid
  JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND NOT t.tgisinternal
    AND (c.relname,t.tgname) IN (
      ('finance_profit_report_job','finance_profit_report_job_guard_trigger_v103'),
      ('finance_profit_report_order','finance_profit_report_order_guard_trigger_v103'),
      ('finance_profit_report_sku','finance_profit_report_sku_guard_trigger_v103'),
      ('finance_profit_report_audit','finance_profit_report_audit_guard_trigger_v103')
    );
  IF installed<>4 THEN
    RAISE EXCEPTION 'V103 guards not installed on all four real tables';
  END IF;
END;
$verify$;

-- LIKE inherits exact V62 NOT NULL, CHECK and DEFAULT, not public FKs.
-- pg_temp shadow names make the actual V103 trigger functions operate on
-- disposable rows under this one explicit transaction.
CREATE TEMP TABLE finance_profit_report_job
  (LIKE public.finance_profit_report_job INCLUDING CONSTRAINTS INCLUDING DEFAULTS);
CREATE TEMP TABLE finance_profit_report_order
  (LIKE public.finance_profit_report_order INCLUDING CONSTRAINTS INCLUDING DEFAULTS);
CREATE TEMP TABLE finance_profit_report_sku
  (LIKE public.finance_profit_report_sku INCLUDING CONSTRAINTS INCLUDING DEFAULTS);
CREATE TEMP TABLE finance_profit_report_audit
  (LIKE public.finance_profit_report_audit INCLUDING CONSTRAINTS INCLUDING DEFAULTS);
CREATE TRIGGER v193_test_job_guard
  BEFORE UPDATE OR DELETE ON finance_profit_report_job
  FOR EACH ROW EXECUTE FUNCTION public.finance_profit_report_job_guard_v103();
CREATE TRIGGER v193_test_order_guard
  BEFORE INSERT OR UPDATE OR DELETE ON finance_profit_report_order
  FOR EACH ROW EXECUTE FUNCTION public.finance_profit_report_order_guard_v103();
CREATE TRIGGER v193_test_sku_guard
  BEFORE INSERT OR UPDATE OR DELETE ON finance_profit_report_sku
  FOR EACH ROW EXECUTE FUNCTION public.finance_profit_report_sku_guard_v103();
CREATE TRIGGER v193_test_audit_guard
  BEFORE UPDATE OR DELETE ON finance_profit_report_audit
  FOR EACH ROW EXECUTE FUNCTION public.finance_profit_report_audit_guard_v103();

CREATE FUNCTION pg_temp.expect_blocked(statement text, expected text)
RETURNS void LANGUAGE plpgsql AS $check$
DECLARE rejected boolean := false;
BEGIN
  BEGIN
    EXECUTE statement;
  EXCEPTION WHEN OTHERS THEN
    IF position(expected IN SQLERRM)=0 THEN RAISE; END IF;
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'V193 allowed a forbidden write: %', expected;
  END IF;
END;
$check$;

INSERT INTO finance_profit_report_job(
  request_key,id,tenant_id,seller_marketplace_id,created_by,
  period_after,period_before,currency_code,total_orders)
VALUES (
  '00000000-0000-4000-8000-000000000190',
  '00000000-0000-4000-8000-000000000193',
  '00000000-0000-4000-8000-000000000192',
  '00000000-0000-4000-8000-000000000191',
  '00000000-0000-4000-8000-000000000197',
  '2026-11-01','2026-12-01','USD',3
);
INSERT INTO finance_profit_report_order(
 tenant_id,job_id,order_id,cohort_position)
VALUES
 ('00000000-0000-4000-8000-000000000192',
  '00000000-0000-4000-8000-000000000193',
  '00000000-0000-4000-8000-000000000194',0),
 ('00000000-0000-4000-8000-000000000192',
  '00000000-0000-4000-8000-000000000193',
  '00000000-0000-4000-8000-000000000195',1),
 ('00000000-0000-4000-8000-000000000192',
  '00000000-0000-4000-8000-000000000193',
  '00000000-0000-4000-8000-000000000196',2);

-- Pending orders may not gain preemptive SKU evidence.
SELECT pg_temp.expect_blocked($stmt$
 INSERT INTO finance_profit_report_sku(
  tenant_id,job_id,order_id,internal_sku,units,signed_sales,
  signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution)
 VALUES('00000000-0000-4000-8000-000000000192',
  '00000000-0000-4000-8000-000000000193',
  '00000000-0000-4000-8000-000000000194','EARLY',1,100,-10,-5,40,45)
$stmt$,'currently captured eligible order');

-- First capture is allowed, including multiple SKU items before batch commit.
UPDATE finance_profit_report_order SET
 state='ELIGIBLE',readiness='ELIGIBLE_PREVIEW',currency_code='USD',
 reviewed_events=1,signed_sales=100,signed_amazon_fees=-10,
 signed_fba_fees=-5,provisional_cogs=40,estimated_contribution=45,
 extract_sha256=repeat('a',64),captured_at=now()
WHERE order_id='00000000-0000-4000-8000-000000000194';
INSERT INTO finance_profit_report_sku(
 tenant_id,job_id,order_id,internal_sku,units,signed_sales,
 signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution)
VALUES
 ('00000000-0000-4000-8000-000000000192',
  '00000000-0000-4000-8000-000000000193',
  '00000000-0000-4000-8000-000000000194','A',1,60,-6,-3,24,27),
 ('00000000-0000-4000-8000-000000000192',
  '00000000-0000-4000-8000-000000000193',
  '00000000-0000-4000-8000-000000000194','B',1,40,-4,-2,16,18);

SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_order SET signed_sales=101,
   estimated_contribution=46
 WHERE order_id='00000000-0000-4000-8000-000000000194'
$stmt$,'immutable after first capture');
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_order SET state='PENDING',
    readiness=NULL,captured_at=NULL,extract_sha256=NULL,
    signed_sales=NULL,signed_amazon_fees=NULL,signed_fba_fees=NULL,
    provisional_cogs=NULL,estimated_contribution=NULL
 WHERE order_id='00000000-0000-4000-8000-000000000194'
$stmt$,'immutable after first capture');
SELECT pg_temp.expect_blocked($stmt$
 DELETE FROM finance_profit_report_order
 WHERE order_id='00000000-0000-4000-8000-000000000194'
$stmt$,'cohort order rows cannot be deleted');
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_sku SET signed_sales=61,estimated_contribution=28
 WHERE internal_sku='A'
$stmt$,'SKU evidence is append-only');
SELECT pg_temp.expect_blocked($stmt$
 DELETE FROM finance_profit_report_sku WHERE internal_sku='A'
$stmt$,'SKU evidence is append-only');
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_job SET total_orders=7
$stmt$,'immutable cohort identity');

-- Job still READY after committing first batch. Late append to position 0
-- must be rejected, while a new position 1 can still be processed.
UPDATE finance_profit_report_job SET processed_orders=1,updated_at=now();
SELECT pg_temp.expect_blocked($stmt$
 INSERT INTO finance_profit_report_sku(
  tenant_id,job_id,order_id,internal_sku,units,signed_sales,
  signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution)
 VALUES('00000000-0000-4000-8000-000000000192',
  '00000000-0000-4000-8000-000000000193',
  '00000000-0000-4000-8000-000000000194','LATE',1,10,0,0,4,6)
$stmt$,'currently captured eligible order');

-- BLOCKED never supports SKU; next ELIGIBLE continues as normal.
UPDATE finance_profit_report_order SET
 state='BLOCKED',readiness='SOURCE_EVIDENCE_UNAVAILABLE',
 extract_sha256=repeat('b',64),captured_at=now()
WHERE order_id='00000000-0000-4000-8000-000000000195';
SELECT pg_temp.expect_blocked($stmt$
 INSERT INTO finance_profit_report_sku(
  tenant_id,job_id,order_id,internal_sku,units,signed_sales,
  signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution)
 VALUES('00000000-0000-4000-8000-000000000192',
  '00000000-0000-4000-8000-000000000193',
  '00000000-0000-4000-8000-000000000195','BLOCKED',1,10,0,0,4,6)
$stmt$,'currently captured eligible order');
UPDATE finance_profit_report_order SET
 state='ELIGIBLE',readiness='ELIGIBLE_PREVIEW',currency_code='USD',
 reviewed_events=1,signed_sales=10,signed_amazon_fees=-1,
 signed_fba_fees=0,provisional_cogs=4,estimated_contribution=5,
 extract_sha256=repeat('c',64),captured_at=now()
WHERE order_id='00000000-0000-4000-8000-000000000196';
INSERT INTO finance_profit_report_sku(
 tenant_id,job_id,order_id,internal_sku,units,signed_sales,
 signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution)
VALUES('00000000-0000-4000-8000-000000000192',
 '00000000-0000-4000-8000-000000000193',
 '00000000-0000-4000-8000-000000000196','C',1,10,-1,0,4,5);

INSERT INTO finance_profit_report_audit(id,tenant_id,job_id,action,processed_orders)
VALUES('00000000-0000-4000-8000-000000000198',
 '00000000-0000-4000-8000-000000000192',
 '00000000-0000-4000-8000-000000000193','BATCH_COMMITTED',1);
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_audit SET processed_orders=2
$stmt$,'audit events are append-only');
SELECT pg_temp.expect_blocked($stmt$
 DELETE FROM finance_profit_report_audit
$stmt$,'audit events are append-only');

UPDATE finance_profit_report_job
 SET state='SUCCEEDED',processed_orders=3,completed_at=now(),updated_at=now();
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_job SET updated_at=now()
$stmt$,'completed job changed');
SELECT pg_temp.expect_blocked($stmt$
 DELETE FROM finance_profit_report_job
$stmt$,'cohort jobs cannot be deleted');
SELECT pg_temp.expect_blocked($stmt$
 INSERT INTO finance_profit_report_sku(
  tenant_id,job_id,order_id,internal_sku,units,signed_sales,
  signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution)
 VALUES('00000000-0000-4000-8000-000000000192',
  '00000000-0000-4000-8000-000000000193',
  '00000000-0000-4000-8000-000000000196','AFTER',1,10,0,0,4,6)
$stmt$,'Late SKU append after V64 job completion prohibited');

DO $counts$
BEGIN
 IF (SELECT count(*) FROM finance_profit_report_order)<>3
 OR (SELECT count(*) FROM finance_profit_report_sku)<>3
 OR (SELECT count(*) FROM finance_profit_report_audit)<>1
 OR (SELECT count(*) FROM finance_profit_report_order WHERE state='BLOCKED')<>1
 OR (SELECT count(*) FROM finance_profit_report_job WHERE state='SUCCEEDED')<>1
 THEN RAISE EXCEPTION 'V193 negative tests mutated frozen snapshot fixtures'; END IF;
END;
$counts$;
ROLLBACK;
\echo PASS V193: real PG18 V103 triggers enforce captured V64 order, SKU, job and audit immutability without base-table writes
