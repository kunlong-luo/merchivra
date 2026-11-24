#!/usr/bin/env python3
"""V215: disposable PG18 V112 real-trigger regression, all writes TEMP/ROLLBACK.

This is only synthetic local metadata and never externally verified landed COGS.
"""
import os
import subprocess

if os.environ.get("MERCHIVRA_V215_DISPOSABLE_ONLY") != "true":
    raise SystemExit("V215 disposable PG18 opt-in mandatory")

def u(i):
    return f"00000000-0000-4000-8000-{i:012x}"

tenant, mp, maker, reviewer, proposer = map(u, [2150, 2151, 2152, 2153, 2154])
source1, source2, source3, plan1, plan2, plan3 = map(
    u, [2155, 2156, 2157, 2158, 2159, 2160])
po, receipt1, receipt2, wrongpo, wrongreceipt = map(
    u, [2161, 2162, 2163, 2164, 2165])
badbatch=u(2166)
source_values = lambda sid: (
    f"('{sid}','{tenant}','{mp}','INBOUND_FREIGHT','MANUAL_INVOICE',"
    f"'TEST-INBOUND-{sid[-8:]}','{'a'*64}','2026-11-23','USD',"
    f"100.000000,'MANUAL_UNVERIFIED','REVIEWED_UNVERIFIED','{maker}',"
    f"'{reviewer}',now(),'synthetic independent metadata review')"
)

sql=f"""
BEGIN;
DO $$ BEGIN
  IF current_setting('server_version_num')::int<180000 THEN
    RAISE EXCEPTION 'PG18 is mandatory';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history
    WHERE version='112' AND success) THEN
    RAISE EXCEPTION 'V112 missing from installed Flyway';
  END IF;
END $$;
-- Shadow real tables; LIKE copies checks/defaults/uniqueness, not external FKs.
CREATE TEMP TABLE finance_cost_source_v91
 (LIKE public.finance_cost_source_v91 INCLUDING DEFAULTS
  INCLUDING CONSTRAINTS INCLUDING INDEXES) ON COMMIT DROP;
CREATE TEMP TABLE finance_cost_allocation_batch_v92
 (LIKE public.finance_cost_allocation_batch_v92 INCLUDING DEFAULTS
  INCLUDING CONSTRAINTS INCLUDING INDEXES) ON COMMIT DROP;
CREATE TEMP TABLE finance_receipt_cost_plan_v112
 (LIKE public.finance_receipt_cost_plan_v112 INCLUDING DEFAULTS
  INCLUDING CONSTRAINTS INCLUDING INDEXES) ON COMMIT DROP;
CREATE TEMP TABLE finance_receipt_cost_line_v112
 (LIKE public.finance_receipt_cost_line_v112 INCLUDING DEFAULTS
  INCLUDING CONSTRAINTS INCLUDING INDEXES) ON COMMIT DROP;
CREATE TEMP TABLE finance_receipt_cost_audit_v112
 (LIKE public.finance_receipt_cost_audit_v112 INCLUDING DEFAULTS
  INCLUDING IDENTITY INCLUDING CONSTRAINTS INCLUDING INDEXES) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_order (id UUID,tenant_id UUID,currency_code CHAR(3))
 ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_receipt
 (id UUID,tenant_id UUID,purchase_order_id UUID)
 ON COMMIT DROP;

CREATE TRIGGER v215_plan_guard BEFORE INSERT OR UPDATE OR DELETE
 ON finance_receipt_cost_plan_v112 FOR EACH ROW
 EXECUTE FUNCTION public.finance_receipt_cost_plan_guard_v112();
CREATE TRIGGER v215_plan_audit AFTER INSERT OR UPDATE
 ON finance_receipt_cost_plan_v112 FOR EACH ROW
 EXECUTE FUNCTION public.finance_receipt_cost_audit_v112();
CREATE TRIGGER v215_line_guard BEFORE INSERT OR UPDATE OR DELETE
 ON finance_receipt_cost_line_v112 FOR EACH ROW
 EXECUTE FUNCTION public.finance_receipt_cost_line_guard_v112();
CREATE TRIGGER v215_v92_cross_guard AFTER INSERT OR UPDATE OF state
 ON finance_cost_allocation_batch_v92 FOR EACH ROW
 EXECUTE FUNCTION public.finance_v92_excludes_receipt_plan_v112();

INSERT INTO finance_cost_source_v91
 (id,tenant_id,seller_marketplace_id,category,source_kind,source_key,
  source_document_sha256,business_date,currency_code,amount,
  evidence_origin,review_state,created_by,reviewed_by,reviewed_at,review_reason) VALUES
 {source_values(source1)},
 {source_values(source2)},
 {source_values(source3)};
INSERT INTO erp_purchase_order VALUES
 ('{po}','{tenant}','USD'),('{wrongpo}','{tenant}','EUR');
INSERT INTO erp_purchase_receipt VALUES
 ('{receipt1}','{tenant}','{po}'),
 ('{receipt2}','{tenant}','{po}'),
 ('{wrongreceipt}','{tenant}','{wrongpo}');

INSERT INTO finance_receipt_cost_plan_v112
 (id,tenant_id,source_id,proposed_by,source_currency,source_amount) VALUES
 ('{plan1}','{tenant}','{source1}','{proposer}','EUR',999.000000);
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_receipt_cost_plan_v112
   WHERE id='{plan1}' AND source_currency='USD' AND source_amount=100) THEN
   RAISE EXCEPTION 'V215 source snapshot not DB-owned'; END IF;
END $$;
INSERT INTO finance_receipt_cost_line_v112 VALUES
 ('{tenant}','{plan1}','{receipt1}',40.000000),
 ('{tenant}','{plan1}','{receipt2}',60.000000);
UPDATE finance_receipt_cost_plan_v112
 SET state='APPROVED_UNVERIFIED',reviewed_by='{reviewer}',
 reviewed_at=now(),review_reason='synthetic owner metadata review'
 WHERE id='{plan1}';
DO $$ BEGIN
 IF (SELECT count(*) FROM finance_receipt_cost_audit_v112
    WHERE plan_id='{plan1}')<>2 THEN
  RAISE EXCEPTION 'V215 missing database-written review audit'; END IF;
END $$;
DO $$ BEGIN
 BEGIN
  UPDATE finance_receipt_cost_line_v112 SET amount=10
   WHERE plan_id='{plan1}' AND receipt_id='{receipt1}';
  RAISE EXCEPTION 'V215 mutable allocation accepted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%immutable%' THEN RAISE; END IF;
 END;
 BEGIN
  DELETE FROM finance_receipt_cost_plan_v112 WHERE id='{plan1}';
  RAISE EXCEPTION 'V215 plan deletion accepted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%cannot be deleted%' THEN RAISE; END IF;
 END;
 BEGIN
  INSERT INTO finance_cost_allocation_batch_v92
   (id,tenant_id,source_id,request_key,target_kind,proposed_by)
   VALUES ('{badbatch}','{tenant}','{source1}','{u(2167)}',
           'ORDER','{proposer}');
  RAISE EXCEPTION 'V215 V92 duplicate ownership accepted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%active receipt proposal%' THEN RAISE; END IF;
 END;
END $$;

INSERT INTO finance_receipt_cost_plan_v112
 (id,tenant_id,source_id,proposed_by,source_currency,source_amount) VALUES
 ('{plan2}','{tenant}','{source2}','{proposer}','USD',100.000000);
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_receipt_cost_line_v112 VALUES
   ('{tenant}','{plan2}','{wrongreceipt}',1);
  RAISE EXCEPTION 'V215 cross currency accepted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%currency mismatch%' THEN RAISE; END IF;
 END;
 BEGIN
  INSERT INTO finance_receipt_cost_line_v112 VALUES
   ('{tenant}','{plan2}','{receipt1}',101.000000);
  RAISE EXCEPTION 'V215 overallocated source';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%overallocated%' THEN RAISE; END IF;
 END;
 BEGIN
  UPDATE finance_receipt_cost_plan_v112
   SET state='APPROVED_UNVERIFIED',reviewed_by='{reviewer}',
       reviewed_at=now(),review_reason='synthetic review no lines'
   WHERE id='{plan2}';
  RAISE EXCEPTION 'V215 empty allocation approved';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%empty or exceed%' THEN RAISE; END IF;
 END;
END $$;
INSERT INTO finance_cost_allocation_batch_v92
 (id,tenant_id,source_id,request_key,target_kind,proposed_by)
 VALUES ('{badbatch}','{tenant}','{source3}','{u(2167)}','ORDER','{proposer}');
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_receipt_cost_plan_v112
   (id,tenant_id,source_id,proposed_by,source_currency,source_amount)
   VALUES ('{plan3}','{tenant}','{source3}','{proposer}','USD',100.000000);
  RAISE EXCEPTION 'V215 existing V92 ownership accepted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%already belongs to active V92%' THEN RAISE; END IF;
 END;
END $$;
ROLLBACK;
"""
p=subprocess.run(
    ["psql","-X","-h","127.0.0.1","-U","merchivra","-d","merchivra",
     "-v","ON_ERROR_STOP=1","-q"],
    input=sql,text=True,capture_output=True,timeout=120)
if p.returncode:
    raise SystemExit("V215 PG18 failed: "+p.stderr[-6500:])
print("PASS V215 PG18 exact V112 triggers: original-currency receipt "
      "conservation, independent audit, immutable lines/plans, "
      "bidirectional V92 conflict, non-capitalization of unverified sources")
