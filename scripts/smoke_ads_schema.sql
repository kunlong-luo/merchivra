-- V66 disposable PG18 constraint smoke, runs in one transaction then ROLLBACK.
-- Uses the local Marketplace and 2 independent Owner memberships created by V65 smoke.
BEGIN;
DO $verify$
DECLARE
  t UUID;
  m UUID;
  foreign_mp UUID;
  creator UUID;
  reviewer UUID;
  profile UUID := gen_random_uuid();
  first_report UUID := gen_random_uuid();
  second_report UUID := gen_random_uuid();
  market_currency CHAR(3);
  rows_before BIGINT;
BEGIN
  SELECT x.tenant_id,x.id,x.currency_code
    INTO t,m,market_currency
    FROM seller_marketplace x ORDER BY created_at,id LIMIT 1;
  IF m IS NULL THEN RAISE EXCEPTION 'V66 requires a local Marketplace from V65 smoke'; END IF;
  SELECT user_id INTO creator FROM membership WHERE tenant_id=t ORDER BY user_id LIMIT 1;
  SELECT user_id INTO reviewer FROM membership
    WHERE tenant_id=t AND user_id<>creator ORDER BY user_id LIMIT 1;
  IF reviewer IS NULL THEN RAISE EXCEPTION 'V66 requires two local Owners'; END IF;
  SELECT id INTO foreign_mp FROM seller_marketplace
    WHERE tenant_id=t AND id<>m ORDER BY id LIMIT 1;
  IF foreign_mp IS NULL THEN RAISE EXCEPTION 'V66 requires a second Marketplace'; END IF;
  SELECT COUNT(*) INTO rows_before FROM finance_ad_spend_reference WHERE tenant_id=t;
  INSERT INTO ads_profile_reference
    (id,tenant_id,seller_marketplace_id,profile_reference,origin,created_by)
    VALUES(profile,t,m,'V66-CI-TEST-PROFILE','MOCK',creator);
  BEGIN
    INSERT INTO ads_profile_reference
      (id,tenant_id,seller_marketplace_id,profile_reference,origin,created_by)
    VALUES(gen_random_uuid(),t,foreign_mp,'V66-CI-TEST-PROFILE','MOCK',creator);
    RAISE EXCEPTION 'Cross Marketplace Ads profile identity was accepted';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO ads_profile_reference
      (id,tenant_id,seller_marketplace_id,profile_reference,origin,created_by)
    VALUES(gen_random_uuid(),t,m,'V66-CI-LIVE-PROFILE','LIVE',creator);
    RAISE EXCEPTION 'Unverified LIVE identity was accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  INSERT INTO ads_report_import
    (id,tenant_id,seller_marketplace_id,profile_id,report_reference,
     report_sha256,evidence_origin,currency_code,from_date,through_date,
     row_count,imported_by)
  VALUES(first_report,t,m,profile,'V66-CI-REPORT-001',repeat('a',64),
         'MOCK',market_currency,'2026-10-01','2026-10-01',1,creator);
  INSERT INTO ads_report_cost_row
    (tenant_id,batch_id,row_number,business_date,campaign_reference,spend_amount,currency_code)
  VALUES(t,first_report,1,'2026-10-01','CAMPAIGN-1',10.250000,market_currency);
  BEGIN
    UPDATE ads_report_import SET
      state='REVIEWED_UNVERIFIED',reviewed_by=creator,reviewed_at=now(),
      review_note='Self approved cost report cannot be trusted'
    WHERE id=first_report;
    RAISE EXCEPTION 'Self-reviewed Ads source passed table constraint';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  UPDATE ads_report_import SET
    state='REVIEWED_UNVERIFIED',reviewed_by=reviewer,reviewed_at=now(),
    review_note='Independently checked offline fixture row'
  WHERE id=first_report;
  INSERT INTO ads_report_reviewed_cost
    (tenant_id,seller_marketplace_id,profile_id,business_date,
     campaign_reference,batch_id,row_number,spend_amount,currency_code)
  VALUES(t,m,profile,'2026-10-01','CAMPAIGN-1',first_report,1,10.250000,market_currency);
  INSERT INTO ads_report_import
    (id,tenant_id,seller_marketplace_id,profile_id,report_reference,
     report_sha256,evidence_origin,currency_code,from_date,through_date,
     row_count,imported_by)
  VALUES(second_report,t,m,profile,'V66-CI-REPORT-002',repeat('b',64),
         'MOCK',market_currency,'2026-10-01','2026-10-01',1,creator);
  INSERT INTO ads_report_cost_row
    (tenant_id,batch_id,row_number,business_date,campaign_reference,spend_amount,currency_code)
  VALUES(t,second_report,1,'2026-10-01','CAMPAIGN-1',15.000000,market_currency);
  BEGIN
    INSERT INTO ads_report_reviewed_cost
      (tenant_id,seller_marketplace_id,profile_id,business_date,
       campaign_reference,batch_id,row_number,spend_amount,currency_code)
    VALUES(t,m,profile,'2026-10-01','CAMPAIGN-1',second_report,1,15,market_currency);
    RAISE EXCEPTION 'Overlapping Ads report cost was counted twice';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  IF (SELECT COUNT(*) FROM ads_report_reviewed_cost WHERE tenant_id=t AND profile_id=profile) <> 1
    THEN RAISE EXCEPTION 'Reviewed Ads costs not uniquely attributed'; END IF;
  IF (SELECT COUNT(*) FROM finance_ad_spend_reference WHERE tenant_id=t) <> rows_before
    THEN RAISE EXCEPTION 'Ads offline evidence polluted manual finance sources'; END IF;
  RAISE NOTICE 'PASS V66 Ads profile, source integrity, reviewer and overlap constraints';
END
$verify$;
ROLLBACK;
