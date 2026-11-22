-- V194/V105 / PG18 real Flyway strict fingerprint-pair proof, disposable fixtures only.
-- Requires the isolated CI PostgreSQL 18 and completed real Spring Flyway startup.
-- All writes are to temporary V62-shaped tables in a rolled-back transaction.
-- V1 hashes are local consistency evidence, never Amazon/Ads/bank proof.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path=pg_temp,public;
DO $proof$
DECLARE actual integer;
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.flyway_schema_history
    WHERE version='104' AND success
  ) THEN RAISE EXCEPTION 'V104 Flyway not installed'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.flyway_schema_history
      WHERE version='105' AND success) THEN
    RAISE EXCEPTION 'V105 strict pair Flyway not installed';
  END IF;
  SELECT count(*) INTO actual
    FROM information_schema.columns
    WHERE table_schema='public'
      AND (
        (table_name='finance_profit_report_order'
         AND column_name IN ('capture_version_v194','capture_sha256_v194'))
        OR
        (table_name='finance_profit_report_sku'
         AND column_name IN ('capture_version_v194','capture_sha256_v194'))
      )
      AND column_default IS NULL AND is_nullable='YES';
  IF actual<>4 THEN
    RAISE EXCEPTION 'V104 requires four nullable, no-default capture columns';
  END IF;
  SELECT count(*) INTO actual FROM pg_catalog.pg_constraint co
    JOIN pg_catalog.pg_class cl ON cl.oid=co.conrelid
    JOIN pg_catalog.pg_namespace ns ON ns.oid=cl.relnamespace
    WHERE ns.nspname='public'
      AND co.conname IN (
        'finance_profit_report_order_capture_pair_v104',
        'finance_profit_report_order_pending_v104',
        'finance_profit_report_sku_capture_pair_v104'
      );
  IF actual<>3 THEN
    RAISE EXCEPTION 'V104 installed capture constraints missing';
  END IF;
  SELECT count(*) INTO actual FROM pg_catalog.pg_constraint co
    JOIN pg_catalog.pg_class cl ON cl.oid=co.conrelid
    JOIN pg_catalog.pg_namespace ns ON ns.oid=cl.relnamespace
    WHERE ns.nspname='public' AND co.conname IN (
      'finance_profit_report_order_capture_pair_strict_v105',
      'finance_profit_report_sku_capture_pair_strict_v105');
  IF actual<>2 THEN
    RAISE EXCEPTION 'V105 installed strict NULL-safe capture checks missing';
  END IF;
END;
$proof$;

CREATE TEMP TABLE finance_profit_report_job
 (LIKE public.finance_profit_report_job INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE TEMP TABLE finance_profit_report_order
 (LIKE public.finance_profit_report_order INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE TEMP TABLE finance_profit_report_sku
 (LIKE public.finance_profit_report_sku INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE TRIGGER test_job_v103 BEFORE UPDATE OR DELETE ON finance_profit_report_job
 FOR EACH ROW EXECUTE FUNCTION public.finance_profit_report_job_guard_v103();
CREATE TRIGGER test_order_v103 BEFORE INSERT OR UPDATE OR DELETE ON finance_profit_report_order
 FOR EACH ROW EXECUTE FUNCTION public.finance_profit_report_order_guard_v103();
CREATE TRIGGER test_sku_v103 BEFORE INSERT OR UPDATE OR DELETE ON finance_profit_report_sku
 FOR EACH ROW EXECUTE FUNCTION public.finance_profit_report_sku_guard_v103();

CREATE FUNCTION pg_temp.expect_blocked(command text,reason text)
RETURNS void LANGUAGE plpgsql AS $test$
DECLARE blocked boolean:=false;
BEGIN
  BEGIN
    EXECUTE command;
  EXCEPTION WHEN OTHERS THEN
    IF position(reason IN SQLERRM)=0 THEN RAISE; END IF;
    blocked:=true;
  END;
  IF NOT blocked THEN
    RAISE EXCEPTION 'V194 fail-closed regression: statement unexpectedly passed: %',reason;
  END IF;
END;
$test$;

INSERT INTO finance_profit_report_job(
 request_key,id,tenant_id,seller_marketplace_id,created_by,
 period_after,period_before,currency_code,total_orders
) VALUES (
 '00000000-0000-4000-8000-000000000201',
 '00000000-0000-4000-8000-000000000202',
 '00000000-0000-4000-8000-000000000203',
 '00000000-0000-4000-8000-000000000204',
 '00000000-0000-4000-8000-000000000205',
 '2026-11-01','2026-12-01','USD',2
);
INSERT INTO finance_profit_report_order(
 tenant_id,job_id,order_id,cohort_position
) VALUES
 ('00000000-0000-4000-8000-000000000203',
  '00000000-0000-4000-8000-000000000202',
  '00000000-0000-4000-8000-000000000206',0),
 ('00000000-0000-4000-8000-000000000203',
  '00000000-0000-4000-8000-000000000202',
  '00000000-0000-4000-8000-000000000207',1);

-- Old snapshots remain without a V1 capture. No migration/backfill is allowed.
DO $legacy$
BEGIN
  IF (SELECT count(*) FROM finance_profit_report_order
     WHERE capture_version_v194 IS NULL AND capture_sha256_v194 IS NULL)<>2
  THEN RAISE EXCEPTION 'Historical cohort unexpectedly gained V194 fingerprints';
  END IF;
END;
$legacy$;

-- Use a separate disposable job for deliberately invalid PENDING inserts.
-- V103 correctly disallows PENDING -> PENDING UPDATE before CHECK constraints.
INSERT INTO finance_profit_report_job(
 request_key,id,tenant_id,seller_marketplace_id,created_by,
 period_after,period_before,currency_code,total_orders
) VALUES (
 '00000000-0000-4000-8000-000000000211',
 '00000000-0000-4000-8000-000000000212',
 '00000000-0000-4000-8000-000000000203',
 '00000000-0000-4000-8000-000000000204',
 '00000000-0000-4000-8000-000000000205',
 '2026-11-01','2026-12-01','USD',1
);
-- Pair, version, SHA syntax and PENDING invariants are real PG18 CHECKs.
SELECT pg_temp.expect_blocked($stmt$
 INSERT INTO finance_profit_report_order(
   tenant_id,job_id,order_id,cohort_position,
   capture_version_v194,capture_sha256_v194
 ) VALUES (
  '00000000-0000-4000-8000-000000000203',
  '00000000-0000-4000-8000-000000000212',
  '00000000-0000-4000-8000-000000000213',0,1,repeat('a',64)
 )
$stmt$,'finance_profit_report_order_pending_v104');
-- Test the V1 pair on a legitimate ELIGIBLE transition instead:
-- a PENDING row with a version also violates the earlier PENDING guard,
-- so it cannot uniquely prove which CHECK expression rejected it.
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_order
 SET state='ELIGIBLE',readiness='ELIGIBLE_PREVIEW',currency_code='USD',
   reviewed_events=1,signed_sales=100,signed_amazon_fees=-10,
   signed_fba_fees=-5,provisional_cogs=40,estimated_contribution=45,
   extract_sha256=repeat('a',64),captured_at=now(),
   capture_version_v194=1,capture_sha256_v194=NULL
 WHERE cohort_position=0
$stmt$,'finance_profit_report_order_capture_pair_strict_v105');
-- The opposite half-pair was also erroneously admitted by V104 UNKNOWN.
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_order
 SET state='ELIGIBLE',readiness='ELIGIBLE_PREVIEW',currency_code='USD',
   reviewed_events=1,signed_sales=100,signed_amazon_fees=-10,
   signed_fba_fees=-5,provisional_cogs=40,estimated_contribution=45,
   extract_sha256=repeat('a',64),captured_at=now(),
   capture_version_v194=NULL,capture_sha256_v194=repeat('b',64)
 WHERE cohort_position=0
$stmt$,'finance_profit_report_order_capture_pair_strict_v105');
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_order
 SET state='ELIGIBLE',readiness='ELIGIBLE_PREVIEW',currency_code='USD',
   reviewed_events=1,signed_sales=100,signed_amazon_fees=-10,
   signed_fba_fees=-5,provisional_cogs=40,estimated_contribution=45,
   extract_sha256=repeat('a',64),captured_at=now(),
   capture_version_v194=2,capture_sha256_v194=repeat('b',64)
 WHERE cohort_position=0
$stmt$,'capture_pair_');
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_order
 SET state='ELIGIBLE',readiness='ELIGIBLE_PREVIEW',currency_code='USD',
   reviewed_events=1,signed_sales=100,signed_amazon_fees=-10,
   signed_fba_fees=-5,provisional_cogs=40,estimated_contribution=45,
   extract_sha256=repeat('a',64),captured_at=now(),
   capture_version_v194=1,capture_sha256_v194=repeat('X',64)
 WHERE cohort_position=0
$stmt$,'capture_pair_');

-- A valid versioned capture and SKU insert still succeed; V103 locks them.
UPDATE finance_profit_report_order
 SET state='ELIGIBLE',readiness='ELIGIBLE_PREVIEW',currency_code='USD',
   reviewed_events=1,signed_sales=100,signed_amazon_fees=-10,
   signed_fba_fees=-5,provisional_cogs=40,estimated_contribution=45,
   extract_sha256=repeat('a',64),captured_at=now(),
   capture_version_v194=1,capture_sha256_v194=repeat('b',64)
 WHERE cohort_position=0;
INSERT INTO finance_profit_report_sku(
 tenant_id,job_id,order_id,internal_sku,units,signed_sales,
 signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution,
 capture_version_v194,capture_sha256_v194
) VALUES (
 '00000000-0000-4000-8000-000000000203',
 '00000000-0000-4000-8000-000000000202',
 '00000000-0000-4000-8000-000000000206',
 'SKU-NEW',1,100,-10,-5,40,45,1,repeat('c',64)
);
SELECT pg_temp.expect_blocked($stmt$
 INSERT INTO finance_profit_report_sku(
  tenant_id,job_id,order_id,internal_sku,units,signed_sales,
  signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution,
  capture_version_v194,capture_sha256_v194
 ) VALUES (
  '00000000-0000-4000-8000-000000000203',
  '00000000-0000-4000-8000-000000000202',
  '00000000-0000-4000-8000-000000000206',
  'SKU-MISSING-HASH',1,10,0,0,4,6,1,NULL
 )
$stmt$,'finance_profit_report_sku_capture_pair_strict_v105');
SELECT pg_temp.expect_blocked($stmt$
 INSERT INTO finance_profit_report_sku(
  tenant_id,job_id,order_id,internal_sku,units,signed_sales,
  signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution,
  capture_version_v194,capture_sha256_v194
 ) VALUES (
  '00000000-0000-4000-8000-000000000203',
  '00000000-0000-4000-8000-000000000202',
  '00000000-0000-4000-8000-000000000206',
  'SKU-MISSING-VERSION',1,10,0,0,4,6,NULL,repeat('d',64)
 )
$stmt$,'finance_profit_report_sku_capture_pair_strict_v105');
SELECT pg_temp.expect_blocked($stmt$
 INSERT INTO finance_profit_report_sku(
  tenant_id,job_id,order_id,internal_sku,units,signed_sales,
  signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution,
  capture_version_v194,capture_sha256_v194
 ) VALUES (
  '00000000-0000-4000-8000-000000000203',
  '00000000-0000-4000-8000-000000000202',
  '00000000-0000-4000-8000-000000000206',
  'SKU-FUTURE-VERSION',1,10,0,0,4,6,2,repeat('d',64)
 )
$stmt$,'capture_pair_');
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_order
 SET capture_sha256_v194=repeat('d',64) WHERE cohort_position=0
$stmt$,'immutable after first capture');
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_sku
 SET capture_sha256_v194=repeat('d',64) WHERE internal_sku='SKU-NEW'
$stmt$,'append-only');

-- A legacy, unversioned row remains distinguishable, not silently promoted.
UPDATE finance_profit_report_order
 SET state='BLOCKED',readiness='SOURCE_EVIDENCE_UNAVAILABLE',
   extract_sha256=repeat('e',64),captured_at=now()
 WHERE cohort_position=1;
DO $verify$
BEGIN
  IF (SELECT count(*) FROM finance_profit_report_order WHERE
    state='BLOCKED' AND capture_version_v194 IS NULL
    AND capture_sha256_v194 IS NULL)<>1
  OR (SELECT count(*) FROM finance_profit_report_sku
      WHERE capture_version_v194=1 AND capture_sha256_v194 IS NOT NULL)<>1
  THEN RAISE EXCEPTION 'Legacy/V1 identity segregation failed'; END IF;
END;
$verify$;
UPDATE finance_profit_report_job
 SET processed_orders=2,state='SUCCEEDED',completed_at=now(),updated_at=now();
SELECT pg_temp.expect_blocked($stmt$
 UPDATE finance_profit_report_order SET capture_sha256_v194=repeat('f',64)
 WHERE cohort_position=0
$stmt$,'unprocessed active cohort');
ROLLBACK;
\echo PASS V194/V105: real PG18 strict NULL-safe order/SKU V1 pairs, legacy nulls, PENDING guard and V103 immutability; no public table writes
