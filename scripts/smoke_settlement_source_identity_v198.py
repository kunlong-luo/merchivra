#!/usr/bin/env python3
"""V198: run the EXACT Java settlement comparison SQL on disposable PG18 TEMP data.

No Amazon calls, no production rows, no DDL on public tables. All fixtures
and the temporary view live inside one transaction and are rolled back.
"""
import os
from pathlib import Path
import re
import subprocess

if os.environ.get("MERCHIVRA_V198_DISPOSABLE_ONLY") != "true":
    raise SystemExit("Set MERCHIVRA_V198_DISPOSABLE_ONLY=true for isolated PG18 only")

java = Path(
    "apps/server/src/main/java/org/merchivra/erp/finance/"
    "SettlementReportComparisonService.java"
).read_text()
match = re.search(r"static final String COMPARISON_SQL=\"\"\"\s*(.*?)\s*\"\"\";",
                  java, re.S)
if not match:
    raise SystemExit("Unable to find exact settlement comparison SQL")
query = match.group(1)
if query.count("?") != 3:
    raise SystemExit("Unexpected comparison parameter shape; do not guess scope")

tenant = "00000000-0000-4000-8000-000000000801"
market = "00000000-0000-4000-8000-000000000802"
report = "00000000-0000-4000-8000-000000000803"
first = "00000000-0000-4000-8000-000000000804"
second = "00000000-0000-4000-8000-000000000805"
rival = "00000000-0000-4000-8000-000000000806"
other_tenant = "00000000-0000-4000-8000-000000000807"
other_market = "00000000-0000-4000-8000-000000000808"
third = "00000000-0000-4000-8000-000000000809"

for value in (tenant, market, report):
    query = query.replace("?", "'" + value + "'::uuid", 1)

commands = [r"""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::integer < 180000 THEN
  RAISE EXCEPTION 'V198 needs real PostgreSQL 18';
 END IF;
END $$;
CREATE TEMP TABLE spapi_settlement_report (
 id UUID, tenant_id UUID, seller_marketplace_id UUID,
 amazon_report_id TEXT, settlement_reference TEXT,
 statement_currency CHAR(3), declared_net NUMERIC(20,6),
 line_count INTEGER
) ON COMMIT DROP;
CREATE TEMP TABLE spapi_settlement_report_line (
 tenant_id UUID, report_id UUID, line_number INTEGER,
 signed_amount NUMERIC(20,6), currency_code CHAR(3),
 external_order_id TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_source_event (
 id UUID, tenant_id UUID, seller_marketplace_id UUID,
 source_family TEXT, data_origin TEXT, amount NUMERIC(20,6),
 currency_code CHAR(3), external_order_id TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_transaction_reference (
 tenant_id UUID, event_id UUID, reference_kind TEXT,
 reference_value TEXT
) ON COMMIT DROP;
"""]
commands.append(f"""
INSERT INTO spapi_settlement_report VALUES
 ('{report}','{tenant}','{market}','RPT-001','SETTLE-001','USD',17.50,2);
INSERT INTO spapi_settlement_report_line VALUES
 ('{tenant}','{report}',1,10.00,'USD'),
 ('{tenant}','{report}',2,7.50,'USD');
INSERT INTO finance_source_event VALUES
 ('{first}','{tenant}','{market}','SPAPI_FINANCES','LIVE',10.00,'USD'),
 ('{second}','{tenant}','{market}','SPAPI_FINANCES','LIVE',7.50,'USD');
INSERT INTO finance_transaction_reference VALUES
 ('{tenant}','{first}','SETTLEMENT_ID','SETTLE-001'),
 ('{tenant}','{second}','SETTLEMENT_ID','SETTLE-001');
CREATE TEMP VIEW v198_comparison AS
{query};
""")

def check(name: str, expression: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9 _-]{1,80}", name):
        raise ValueError("Unsafe test label")
    commands.append(f"""
DO $$ DECLARE correct boolean; BEGIN
  SELECT ({expression}) INTO correct FROM v198_comparison;
  IF correct IS DISTINCT FROM TRUE THEN
    RAISE EXCEPTION 'V198 failed: {name}';
  END IF;
END $$;
""")

check("clean local totals remain unverified",
      "line_net=17.50 AND finance_net=17.50 AND matched_count=2 "
      "AND expected_line_count=2 AND actual_line_count=2 "
      "AND foreign_line_count=0 AND shared_report_count=0 "
      "AND multi_settlement_event_count=0 AND foreign_count=0")

commands.append(f"""
-- The same source event can carry multiple SETTLEMENT_ID references because
-- the V31 PK only excludes an identical value. V198 must make this visible.
INSERT INTO finance_transaction_reference VALUES
 ('{tenant}','{first}','SETTLEMENT_ID','SETTLE-ALTERNATE');
""")
check("one event belongs to two settlement identifiers",
      "line_net=17.50 AND finance_net=17.50 "
      "AND multi_settlement_event_count=1 AND matched_count=2")
commands.append(f"""
DELETE FROM finance_transaction_reference
 WHERE tenant_id='{tenant}' AND event_id='{first}'
   AND reference_value='SETTLE-ALTERNATE';

-- Same settlement reference under a DIFFERENT report document is ambiguous.
INSERT INTO spapi_settlement_report VALUES
 ('{rival}','{tenant}','{market}','RPT-002','SETTLE-001','USD',17.50,2);
""")
check("same settlement claimed by second report",
      "shared_report_count=1 AND multi_settlement_event_count=0")
commands.append(f"""
DELETE FROM spapi_settlement_report WHERE id='{rival}';
-- A different tenant or Marketplace cannot become a competing claim.
INSERT INTO spapi_settlement_report VALUES
 ('{rival}','{other_tenant}','{market}','RPT-X','SETTLE-001','USD',17.50,2),
 ('{third}','{tenant}','{other_market}','RPT-M','SETTLE-001','USD',17.50,2);
INSERT INTO finance_source_event VALUES
 ('{third}','{tenant}','{other_market}','SPAPI_FINANCES','LIVE',99.00,'USD');
INSERT INTO finance_transaction_reference VALUES
 ('{tenant}','{third}','SETTLEMENT_ID','SETTLE-001'),
 ('{tenant}','{first}','DISBURSEMENT_ID','OTHER-REF');
""")
check("cross tenant marketplace and other reference kind ignored",
      "shared_report_count=0 AND multi_settlement_event_count=0 "
      "AND matched_count=2 AND finance_net=17.50")

commands.append(f"""
-- A zero-valued lost report line can leave the SAME amount total, but the
-- immutable import line_count must detect the missing row.
UPDATE spapi_settlement_report SET line_count=3 WHERE id='{report}';
INSERT INTO spapi_settlement_report_line VALUES
 ('{tenant}','{report}',3,0,'USD');
""")
check("zero valued row present", "expected_line_count=3 AND actual_line_count=3")
commands.append(f"""
DELETE FROM spapi_settlement_report_line
 WHERE report_id='{report}' AND line_number=3;
""")
check("zero valued line missing despite equal total",
      "expected_line_count=3 AND actual_line_count=2 AND line_net=17.50")
commands.append(f"""
UPDATE spapi_settlement_report SET line_count=2 WHERE id='{report}';
UPDATE spapi_settlement_report_line SET currency_code='EUR'
 WHERE report_id='{report}' AND line_number=2;
""")
check("mixed report line currency despite equal total",
      "foreign_line_count=1 AND line_net=17.50 AND finance_net=17.50")
# V199: V54 permits a NULL report statement total. Show that the exact
# Java SQL preserves NULL instead of converting it to a numeric zero; Java
# decision tests must block such a source even with equal line/Finances sums.
commands.append(f"""
UPDATE spapi_settlement_report SET declared_net=NULL WHERE id='{report}';
UPDATE spapi_settlement_report_line SET currency_code='USD'
 WHERE report_id='{report}' AND line_number=2;
""")
check("absent declared net is still null with matching sums",
      "declared_net IS NULL AND line_net=17.50 AND finance_net=17.50 "
      "AND actual_line_count=2 AND foreign_line_count=0")
commands.append(f"""
UPDATE spapi_settlement_report SET declared_net=17.50 WHERE id='{report}';
UPDATE spapi_settlement_report_line SET external_order_id='ORDER-A'
 WHERE report_id='{report}' AND line_number=1;
UPDATE spapi_settlement_report_line SET external_order_id='ORDER-B'
 WHERE report_id='{report}' AND line_number=2;
UPDATE finance_source_event SET external_order_id='ORDER-A' WHERE id='{first}';
UPDATE finance_source_event SET external_order_id='ORDER-B' WHERE id='{second}';
""")
check("V271 correct raw order buckets", "NOT order_bucket_conflict")
commands.append(f"""
UPDATE finance_source_event SET amount=9.00 WHERE id='{first}';
UPDATE finance_source_event SET amount=8.50 WHERE id='{second}';
""")
check("V271 offsetting order errors", "finance_net=17.50 AND line_net=17.50 AND order_bucket_conflict")
commands.append(f"""
UPDATE finance_source_event SET amount=10.00,external_order_id=NULL WHERE id='{first}';
UPDATE finance_source_event SET amount=7.50 WHERE id='{second}';
""")
check("V271 missing order reference not allocated", "finance_net=17.50 AND order_bucket_conflict")
commands.append(f"""
UPDATE spapi_settlement_report_line SET external_order_id=NULL
 WHERE report_id='{report}' AND line_number=1;
""")
check("V271 matching unassigned bucket remains unverified", "NOT order_bucket_conflict")
commands.append(f"""
UPDATE finance_source_event SET external_order_id='ORDER-B' WHERE id='{first}';
UPDATE spapi_settlement_report_line SET signed_amount=0
 WHERE report_id='{report}' AND line_number=1;
UPDATE spapi_settlement_report_line SET signed_amount=17.50
 WHERE report_id='{report}' AND line_number=2;
""")
check("V271 missing zero bucket not silently ignored", "finance_net=17.50 AND line_net=17.50 AND order_bucket_conflict")
commands.append("ROLLBACK;\n")
result = subprocess.run(
    ["psql", "-X", "-h", "127.0.0.1", "-U", "merchivra", "-d", "merchivra",
     "-v", "ON_ERROR_STOP=1", "-q"],
    input="".join(commands), text=True, capture_output=True,
    check=False, timeout=100
)
if result.returncode:
    raise SystemExit("V198 disposable PG18 check failed:\n" + result.stderr[-4000:])
print("PASS V198 real PG18 exact Java SQL: unique source claim, report identity, "
      "line completeness, currency and tenant/Marketplace scope")

print("PASS V271 real PG18 exact Java SQL: order buckets, offsetting errors, NULL and absent zero bucket")
