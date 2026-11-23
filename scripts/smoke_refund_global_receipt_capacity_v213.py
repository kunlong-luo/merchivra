#!/usr/bin/env python3
"""V213: exact Java SQL global sale-claim audit on disposable PostgreSQL 18.

The V106 real insertion trigger is already exercised by prior A3 checks.
These TEMP shadow tables + view test V213's bounded, scoped SELECT only.
Never infer a true Amazon shipment or landed purchase cost.
"""
import os
import subprocess
from pathlib import Path

if os.environ.get("MERCHIVRA_V213_DISPOSABLE_ONLY") != "true":
    raise SystemExit("V213: disposable PG18 opt-in is mandatory")

source = Path("apps/server/src/main/java/org/merchivra/erp/finance/"
              "RefundGlobalReceiptCapacityServiceV213.java").read_text()
marker = 'static final String GLOBAL_RECEIPT_SCOPE_SQL="""\n'
if source.count(marker)!=1:
    raise SystemExit("Missing source-exact V213 Java SQL")
query = source.split(marker,1)[1].split('\n        """;',1)[0].strip()
if query.count("?")!=1 or query.count("%s")!=1 or not query.endswith("LIMIT 1001"):
    raise SystemExit("V213 read bound, tenant parameter or source SQL changed")
def uid(n):
    return "00000000-0000-4000-8000-"+f"{n:012x}"
tenant=uid(2130)
foreign=uid(2131)
receipt_a=uid(2132)
receipt_b=uid(2133)
order_a=uid(2134)
order_b=uid(2135)
order_c=uid(2136)
def scoped():
    q=query.replace("%s",f"'{receipt_a}','{receipt_b}'")
    return q.replace("?",f"'{tenant}'")
select=scoped()
if "?" in select or "%s" in select:raise SystemExit("V213 SQL unbound")

sql=f"""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::int<180000 THEN
  RAISE EXCEPTION 'V213 requires real PostgreSQL 18';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history
   WHERE version='111' AND success) THEN
  RAISE EXCEPTION 'V213 requires all real Flyway migrations through V111';
 END IF;
END $$;
CREATE TEMP TABLE finance_sale_lot_claim_v106 (
  id UUID,tenant_id UUID,receipt_id UUID,sales_order_id UUID,
  assigned_quantity INTEGER,snapshot_accepted_quantity INTEGER,
  snapshot_currency CHAR(3),snapshot_unit_cost NUMERIC(20,6),
  integrity_state TEXT
) ON COMMIT DROP;
CREATE TEMP VIEW finance_sale_lot_integrity_v106 AS
 SELECT tenant_id,id AS claim_id,integrity_state
 FROM finance_sale_lot_claim_v106;
INSERT INTO finance_sale_lot_claim_v106 VALUES
 ('{uid(2140)}','{tenant}','{receipt_a}','{order_a}',
  2,5,'USD',7.50,'LOCAL_LINK_INTACT_UNVERIFIED'),
 ('{uid(2141)}','{tenant}','{receipt_a}','{order_b}',
  2,5,'USD',7.50,'LOCAL_LINK_INTACT_UNVERIFIED'),
 ('{uid(2142)}','{tenant}','{receipt_a}','{order_c}',
  1,5,'USD',7.50,'RETRACTED'),
 ('{uid(2143)}','{tenant}','{receipt_b}','{order_a}',
  1,4,'EUR',9.25,'LOCAL_LINK_INTACT_UNVERIFIED'),
 ('{uid(2144)}','{foreign}','{receipt_a}','{order_b}',
  999,5,'USD',7.50,'LOCAL_LINK_INTACT_UNVERIFIED');
CREATE TEMP TABLE v213_receipts AS
{select};
DO $$ BEGIN
 IF (SELECT count(*) FROM v213_receipts)<>2 THEN
  RAISE EXCEPTION 'V213 lost/multiplied receipts';
 END IF;
 IF (SELECT count(*) FROM v213_receipts WHERE receipt_id='{receipt_a}'
    AND claim_count=3 AND order_count=3 AND assigned_units=5
    AND accepted_units=5 AND accepted_versions=1
    AND currency_count=1 AND price_count=1 AND stale_count=1
    AND reference_currency='USD' AND reference_unit_cost=7.50)<>1 THEN
  RAISE EXCEPTION 'V213 cross-order receipt scope or retraction capacity wrong';
 END IF;
 IF (SELECT count(*) FROM v213_receipts WHERE receipt_id='{receipt_b}'
    AND claim_count=1 AND assigned_units=1 AND accepted_units=4
    AND reference_currency='EUR' AND reference_unit_cost=9.25)<>1 THEN
  RAISE EXCEPTION 'V213 original-currency purchase reference mixed';
 END IF;
END $$;

-- Drift in QC source snapshot or original purchase price must be visible.
UPDATE finance_sale_lot_claim_v106
 SET snapshot_accepted_quantity=4,snapshot_unit_cost=7.75
 WHERE id='{uid(2142)}';
CREATE TEMP TABLE v213_changed AS
{select};
DO $$ BEGIN
 IF (SELECT count(*) FROM v213_changed WHERE receipt_id='{receipt_a}'
    AND accepted_versions=2 AND price_count=2)<>1 THEN
  RAISE EXCEPTION 'V213 historical receipt/cost source mismatch disappeared';
 END IF;
END $$;
ROLLBACK;
"""
run=subprocess.run([
    "psql","-X","-h","127.0.0.1","-U","merchivra",
    "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
    input=sql,text=True,capture_output=True,timeout=120)
if run.returncode:
    raise SystemExit("V213 PG18 source-exact SQL failed: "+run.stderr[-6000:])
print("PASS V213 PG18 exact global V106 receipt-capacity SQL: "
      "three orders share same receipt without multiplication, "
      "RETRACTED still consumes original accepted units, "
      "USD/EUR reference separated, tenant isolated, "
      "QC snapshot and purchase price version drift re-detected, "
      "TEMP V106 VIEW shadow only, ROLLBACK")
