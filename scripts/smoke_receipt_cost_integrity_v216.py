#!/usr/bin/env python3
"""V216 exact Java receipt/source SELECT against disposable PG18 TEMP fixtures.

No real invoices, Amazon evidence, verified landed costs or accounting posting.
V112 installed triggers were tested by V215; this script checks read-time SQL.
"""
import os
import subprocess
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen

if os.environ.get("MERCHIVRA_V216_DISPOSABLE_ONLY") != "true":
    raise SystemExit("V216 requires disposable PG18 opt-in")

source = Path(
    "apps/server/src/main/java/org/merchivra/erp/finance/"
    "ReceiptCostIntegrityServiceV216.java").read_text()
marker = 'static final String RECEIPT_SCOPE_SQL = """\n'
if source.count(marker) != 1:
    raise SystemExit("V216 exact Java SQL declaration missing")
query = source.split(marker, 1)[1].split('\n        """;', 1)[0].strip()
if query.count("?") != 3 or not query.endswith("LIMIT 51"):
    raise SystemExit("V216 tenant/receipt/Marketplace bind or row cap changed")

def u(i):
    return f"00000000-0000-4000-8000-{i:012x}"

tenant, foreign, mp, other_mp = map(u, [21600, 21601, 21602, 21603])
receipt, receipt2, plan1, plan2 = map(u, [21604, 21605, 21606, 21607])
source1, source2, source3, maker, reviewer = map(
    u, [21608, 21609, 21610, 21611, 21612])
po, v92_id = map(u, [21613, 21614])

def select(t=tenant, r=receipt, marketplace=mp):
    sql = query
    for bound in (t, r, marketplace):
        sql = sql.replace("?", f"'{bound}'", 1)
    if "?" in sql:
        raise SystemExit("Unbound V216 scoped source parameter")
    return sql

sql = f"""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::integer < 180000 THEN
  RAISE EXCEPTION 'V216 needs real PostgreSQL 18';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history
     WHERE version='112' AND success) THEN
  RAISE EXCEPTION 'V216 Flyway V112 not installed';
 END IF;
END $$;
CREATE TEMP TABLE finance_receipt_cost_plan_v112 (
 id uuid,tenant_id uuid,source_id uuid,state text,
 source_currency char(3),source_amount numeric(20,6),
 proposed_by uuid,reviewed_by uuid
) ON COMMIT DROP;
CREATE TEMP TABLE finance_receipt_cost_line_v112 (
 tenant_id uuid,plan_id uuid,receipt_id uuid,amount numeric(20,6)
) ON COMMIT DROP;
CREATE TEMP TABLE finance_cost_source_v91 (
 id uuid,tenant_id uuid,seller_marketplace_id uuid,
 review_state text,currency_code char(3),category text,
 amount numeric(20,6),reviewed_by uuid
) ON COMMIT DROP;
CREATE TEMP TABLE finance_cost_allocation_batch_v92 (
 id uuid,tenant_id uuid,source_id uuid,state text
) ON COMMIT DROP;
CREATE TEMP TABLE finance_receipt_cost_audit_v112 (
 tenant_id uuid,plan_id uuid,action text,actor_id uuid
) ON COMMIT DROP;
CREATE TEMP TABLE finance_cost_source_audit_v91 (
 tenant_id uuid,source_id uuid,action text,actor_id uuid
) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_receipt (
 id uuid,tenant_id uuid,purchase_order_id uuid
) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_order (
 id uuid,tenant_id uuid,currency_code char(3)
) ON COMMIT DROP;
INSERT INTO erp_purchase_order VALUES ('{po}','{tenant}','USD');
INSERT INTO erp_purchase_receipt VALUES
 ('{receipt}','{tenant}','{po}'),('{receipt2}','{tenant}','{po}');
INSERT INTO finance_cost_source_v91 VALUES
 ('{source1}','{tenant}','{mp}','REVIEWED_UNVERIFIED','USD',
  'INBOUND_FREIGHT',100,'{reviewer}'),
 ('{source2}','{tenant}','{mp}','REVIEWED_UNVERIFIED','EUR',
  'IMPORT_DUTY',80,'{reviewer}'),
 ('{source3}','{tenant}','{other_mp}','REVIEWED_UNVERIFIED','USD',
  'INBOUND_FREIGHT',30,'{reviewer}'),
 ('{source1}','{foreign}','{mp}','STAGED','USD',
  'INBOUND_FREIGHT',999,'{reviewer}');
INSERT INTO finance_receipt_cost_plan_v112 VALUES
 ('{plan1}','{tenant}','{source1}','APPROVED_UNVERIFIED','USD',100,
  '{maker}','{reviewer}'),
 ('{plan2}','{tenant}','{source2}','APPROVED_UNVERIFIED','EUR',80,
  '{maker}','{reviewer}');
INSERT INTO finance_receipt_cost_line_v112 VALUES
 ('{tenant}','{plan1}','{receipt}',40),
 ('{tenant}','{plan1}','{receipt2}',50),
 ('{tenant}','{plan2}','{receipt}',25);
INSERT INTO finance_cost_allocation_batch_v92 VALUES
 ('{v92_id}','{tenant}','{source2}','APPROVED_UNVERIFIED');
INSERT INTO finance_receipt_cost_audit_v112 VALUES
 ('{tenant}','{plan1}','PENDING_REVIEW','{maker}'),
 ('{tenant}','{plan1}','APPROVED_UNVERIFIED','{reviewer}'),
 ('{tenant}','{plan2}','PENDING_REVIEW','{maker}');
INSERT INTO finance_cost_source_audit_v91 VALUES
 ('{tenant}','{source1}','REVIEWED_UNVERIFIED','{reviewer}');
CREATE TEMP TABLE v216_initial AS
{select()};
DO $$ BEGIN
 IF (SELECT count(*) FROM v216_initial)<>2 THEN
   RAISE EXCEPTION 'V216 expected two exact tenant and marketplace plans';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM v216_initial
   WHERE plan_id='{plan1}' AND receipt_amount=40
     AND allocated=90 AND line_count=2 AND currency_count=2
     AND valid_receipt_count=2 AND v92_count=0
     AND initial_audit AND decision_audit AND review_audit) THEN
   RAISE EXCEPTION 'V216 source-wide sums, metadata or audit broken';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM v216_initial
   WHERE plan_id='{plan2}' AND receipt_amount=25
     AND allocated=25 AND line_count=1 AND currency_count=0
     AND v92_count=1 AND initial_audit
     AND NOT decision_audit AND NOT review_audit) THEN
   RAISE EXCEPTION 'V216 conflicting V92/currency/decision not exposed';
 END IF;
END $$;
-- Changing current PO currency must invalidate *whole plan* read-time.
UPDATE erp_purchase_order SET currency_code='EUR' WHERE id='{po}';
CREATE TEMP TABLE v216_changed AS
{select()};
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM v216_changed
   WHERE plan_id='{plan1}' AND currency_count=0
     AND allocated=90) THEN
   RAISE EXCEPTION 'V216 PO current currency drift ignored';
 END IF;
END $$;
CREATE TEMP TABLE v216_foreign_mp AS {select(marketplace=other_mp)};
CREATE TEMP TABLE v216_foreign_tenant AS {select(t=foreign)};
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM v216_foreign_mp)
    OR EXISTS(SELECT 1 FROM v216_foreign_tenant) THEN
   RAISE EXCEPTION 'V216 source escaped tenant or Marketplace boundary';
 END IF;
END $$;
ROLLBACK;
"""
p = subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
                    "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
                   input=sql,text=True,capture_output=True,timeout=120)
if p.returncode:
    raise SystemExit("V216 PG18 source-exact SQL failed: " + p.stderr[-6500:])
# The same live isolated Spring HTTP process must reject unauthenticated access.
# Do not interpret a missing service, HTTP 500 or a redirect as an authorization pass.
endpoint = ("http://127.0.0.1:8080/api/v1/finance/marketplaces/"
            "00000000-0000-4000-8000-000000000204/purchase-receipts/"
            "00000000-0000-4000-8000-000000000202/manual-cost-integrity")
try:
    with urlopen(endpoint, timeout=10) as response:
        raise SystemExit(f"V216 unauthorized GET unexpectedly returned {response.status}")
except HTTPError as error:
    if error.code != 401:
        raise SystemExit(f"V216 unauthorized GET expected 401, got {error.code}")
print("PASS V216 anonymous Owner GET 401")

print("PASS V216 PG18 exact receipt/V91/V92 evidence SQL: source-wide "
      "original currency, independent audit, V92 conflict, "
      "PO drift, tenant/Marketplace isolation, synthetic TEMP ROLLBACK")
