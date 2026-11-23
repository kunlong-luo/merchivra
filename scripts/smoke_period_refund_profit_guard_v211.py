#!/usr/bin/env python3
"""V211: execute all THREE Java period-guard SQL contracts on real PG18.

No fake Amazon/ledger truth. TEMP synthetic tables shadow production tables,
actual Java SQL is taken from source, and every write is ROLLBACKed.
"""
import os
import re
import subprocess
from pathlib import Path

if os.environ.get("MERCHIVRA_V211_DISPOSABLE_ONLY") != "true":
    raise SystemExit("V211 requires explicit disposable PostgreSQL18 opt-in")

java = Path("apps/server/src/main/java/org/merchivra/erp/finance/"
            "PeriodRefundProfitGuardServiceV211.java").read_text()

def actual_sql(name, binds):
    marker = 'static final String ' + name + '="""\n'
    if java.count(marker) != 1:
        raise SystemExit("V211 source SQL marker changed: " + name)
    source = java.split(marker, 1)[1].split('\n        """;', 1)[0].strip()
    if source.count("?") != len(binds):
        raise SystemExit("V211 bind count drift: " + name)
    if not source.endswith("LIMIT 1001") and not source.endswith("LIMIT 101"):
        raise SystemExit("V211 query must bound full evidence, not paginate")
    for value in binds:
        source = source.replace("?", "'" + value + "'", 1)
    if "?" in source:
        raise SystemExit("V211 unbound parameter")
    return source

def uid(n):
    return "00000000-0000-4000-8000-" + f"{n:012x}"

ids = {key:uid(0xE2110+i) for i,key in enumerate((
    "tenant", "foreign", "marketplace", "other_marketplace", "job",
    "other_job", "old_job", "a", "b", "c", "e1", "e2", "e3",
    "e4", "e5", "e6", "foreign_event", "other_event", "unclassified_event"))}
d1 = "2026-11-01T00:00:00+00:00"
d2 = "2026-12-01T00:00:00+00:00"

window = actual_sql("PERIOD_SOURCE_SQL",
    [ids["job"],ids["tenant"],ids["marketplace"],d1,d2])
cohort = actual_sql("REFUND_COHORT_ORDERS_SQL",
    [ids["tenant"],ids["job"],ids["marketplace"]])
overlap = actual_sql("OTHER_REPORT_OVERLAPS_SQL",
    [ids["tenant"],ids["job"],ids["marketplace"],"2026-12-01","2026-11-01"])

sql = """
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::int < 180000 THEN
  RAISE EXCEPTION 'V211 actual PostgreSQL 18 is mandatory';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history
    WHERE version='111' AND success) THEN
  RAISE EXCEPTION 'V211 requires all existing Flyway migrations through V111';
 END IF;
END $$;
CREATE TEMP TABLE finance_profit_report_order (
  tenant_id UUID,job_id UUID,order_id UUID
) ON COMMIT DROP;
CREATE TEMP TABLE finance_profit_report_job (
  tenant_id UUID,id UUID,seller_marketplace_id UUID,
  period_after DATE,period_before DATE
) ON COMMIT DROP;
CREATE TEMP TABLE finance_source_event (
  id UUID,tenant_id UUID,seller_marketplace_id UUID,
  posted_at TIMESTAMPTZ,currency_code CHAR(3),
  amount NUMERIC(20,6),source_family TEXT,data_origin TEXT,category TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_event_link_review (
  tenant_id UUID,event_id UUID,order_id UUID,state TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_refund_item_review (
  tenant_id UUID,sales_order_id UUID,event_id UUID
) ON COMMIT DROP;

INSERT INTO finance_profit_report_job VALUES
 ('@tenant@','@job@','@marketplace@','2026-11-01','2026-12-01'),
 ('@tenant@','@other_job@','@marketplace@','2026-11-15','2026-12-15'),
 ('@tenant@','@old_job@','@marketplace@','2026-08-01','2026-09-01');
INSERT INTO finance_profit_report_order VALUES
 ('@tenant@','@job@','@a@'),
 ('@tenant@','@job@','@b@'),
 ('@tenant@','@other_job@','@a@'),
 ('@tenant@','@old_job@','@b@'),
 ('@foreign@','@job@','@c@');

INSERT INTO finance_source_event VALUES
 ('@e1@','@tenant@','@marketplace@','2026-11-10T12:00:00Z',
  'USD',-20,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@e2@','@tenant@','@marketplace@','2026-12-05T12:00:00Z',
  'USD',-10,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@e3@','@tenant@','@marketplace@','2026-11-12T12:00:00Z',
  'USD',-5,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@e4@','@tenant@','@marketplace@','2026-11-13T12:00:00Z',
  'USD',-3,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@e5@','@tenant@','@marketplace@','2026-11-14T12:00:00Z',
  'USD',-7,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@e6@','@tenant@','@marketplace@','2026-11-16T12:00:00Z',
  'EUR',-12,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@foreign_event@','@foreign@','@marketplace@','2026-11-17T12:00:00Z',
  'USD',-150,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@other_event@','@tenant@','@other_marketplace@','2026-11-18T12:00:00Z',
  'USD',-80,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@unclassified_event@','@tenant@','@marketplace@','2026-11-19T12:00:00Z',
  'USD',-90,'SPAPI_FINANCES','LIVE','UNCLASSIFIED');
INSERT INTO finance_event_link_review VALUES
 ('@tenant@','@e1@','@a@','MATCHED'),
 ('@tenant@','@e2@','@a@','MATCHED'),
 ('@tenant@','@e3@','@c@','MATCHED'),
 ('@tenant@','@e5@','@a@','MATCHED'),
 ('@tenant@','@e5@','@b@','MATCHED'),
 ('@tenant@','@e6@','@b@','MATCHED'),
 ('@foreign@','@foreign_event@','@a@','MATCHED'),
 ('@tenant@','@other_event@','@a@','MATCHED');
INSERT INTO finance_refund_item_review VALUES
 ('@tenant@','@b@','@e6@'),
 ('@foreign@','@c@','@foreign_event@');

CREATE TEMP TABLE v211_period AS
"""
sql += window + ";\n"
sql += "CREATE TEMP TABLE v211_cohort AS\n" + cohort + ";\n"
sql += "CREATE TEMP TABLE v211_overlaps AS\n" + overlap + ";\n"
sql += """
DO $$ BEGIN
 IF (SELECT count(*) FROM v211_period) <> 5
 OR (SELECT coalesce(sum(amount),0) FROM v211_period) <> -47
 OR (SELECT count(*) FROM v211_period
     WHERE currency_code='USD') <> 4
 OR (SELECT count(*) FROM v211_period
     WHERE currency_code='EUR') <> 1 THEN
  RAISE EXCEPTION 'V211 period LIVE sources were lost/multiplied/mixed';
 END IF;
 IF (SELECT count(*) FROM v211_period WHERE match_count=0) <> 1
 OR (SELECT count(*) FROM v211_period WHERE match_count=2) <> 1
 OR (SELECT count(*) FROM v211_period WHERE NOT in_frozen_cohort) <> 2 THEN
  RAISE EXCEPTION 'V211 source orphan/multiple link/out-of-cohort flags wrong';
 END IF;
 IF (SELECT count(*) FROM v211_cohort)<>2
 OR NOT EXISTS(SELECT 1 FROM v211_cohort WHERE order_id='@a@')
 OR NOT EXISTS(SELECT 1 FROM v211_cohort WHERE order_id='@b@') THEN
  RAISE EXCEPTION 'V211 all-time cohort refund order discovery incomplete';
 END IF;
 IF (SELECT count(*) FROM v211_overlaps)<>1
 OR NOT EXISTS(SELECT 1 FROM v211_overlaps
   WHERE job_id='@other_job@' AND order_id='@a@') THEN
  RAISE EXCEPTION 'V211 report overlap includes wrong tenant/date or same job';
 END IF;
END $$;

-- Later refunds (Dec) must remain visible to the ORDER lifetime cohort,
-- but never be silently subtracted into a November order-created cohort.
UPDATE finance_source_event
 SET amount=-1000
 WHERE id='@e2@' AND tenant_id='@tenant@';
CREATE TEMP TABLE v211_period_recheck AS
"""
sql += window + ";\n"
sql += """
DO $$ BEGIN
 IF (SELECT count(*) FROM v211_period_recheck)<>5
    OR (SELECT sum(amount) FROM v211_period_recheck)<>-47 THEN
  RAISE EXCEPTION 'V211 post-window event polluted November source snapshot';
 END IF;
END $$;
ROLLBACK;
"""
for key,value in ids.items():
    sql = sql.replace("@"+key+"@",value)
if re.search(r"@[a-z0-9_]+@",sql):
    raise SystemExit("V211 fixture substitution missing")

p = subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
    "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
    input=sql,text=True,capture_output=True,timeout=120)
if p.returncode:
    raise SystemExit("V211 real PG18 source-SQL failure: "+p.stderr[-6000:])
print("PASS V211 PG18 source-exact period/refund cohort/overlap SQL: "
      "source not multiplied by double links, unmatched and out-of-cohort "
      "refunds visible, split USD/EUR, cross-period December refund retained "
      "for lifetime V210 order checks, overlapping V64 reports are snapshots "
      "not journal entries, tenant/marketplace isolation, TEMP/ROLLBACK")
