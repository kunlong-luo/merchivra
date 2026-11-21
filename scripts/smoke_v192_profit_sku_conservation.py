#!/usr/bin/env python3
"""V192: execute the REAL V188 frozen-order/SKU aggregate SQL on isolated PG18.

All fixture writes go to session-local TEMP tables. V62 public tables, historical
financial rows, Seller/Ads access, and the financial ledger remain untouched.
No real credentials or platform data are ever fetched, logged, or created.
"""
import os
from pathlib import Path
import re
import subprocess

if os.getenv("MERCHIVRA_V192_DISPOSABLE_ONLY") != "true":
    raise SystemExit("V192 PG18 proof requires explicit disposable-only approval")
if (os.getenv("DB_HOST"), os.getenv("DB_USER"), os.getenv("DB_NAME")) != (
    "127.0.0.1", "merchivra", "merchivra"
):
    raise SystemExit("V192 PG18 proof permits only the isolated local CI endpoint")

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/server/src/main/java/org/merchivra/erp/finance/PeriodProfitWaterfallServiceV188.java"
source = JAVA.read_text(encoding="utf-8")
m = re.search(r'var frozen=jdbc\.query\("""\s*(.*?)\s*""",', source, re.S)
if not m:
    raise SystemExit("Cannot locate the single production V188 frozen aggregate SQL")
query = m.group(1)
if query.count("?") != 3 or "sku_mismatch_count" not in query or "LEFT JOIN LATERAL" not in query:
    raise SystemExit("V188 SQL shape/parameter count drifted; review the PG18 proof")
tenant = "00000000-0000-4000-8000-000000000192"
job = "00000000-0000-4000-8000-000000000193"
a = "00000000-0000-4000-8000-000000000194"
b = "00000000-0000-4000-8000-000000000195"
c = "00000000-0000-4000-8000-000000000196"
for value in ("'USD'", f"'{tenant}'::uuid", f"'{job}'::uuid"):
    query = query.replace("?", value, 1)

# PG18 LIKE copies actual V62 CHECK and NOT NULL constraints, but deliberately
# NOT production FK links. pg_temp-first shadowing executes the exact Java SQL.
setup = f"""
BEGIN;
SET LOCAL search_path = pg_temp, public;
SET LOCAL client_min_messages = WARNING;
CREATE TEMP TABLE finance_profit_report_order
  (LIKE public.finance_profit_report_order INCLUDING CONSTRAINTS);
CREATE TEMP TABLE finance_profit_report_sku
  (LIKE public.finance_profit_report_sku INCLUDING CONSTRAINTS);
INSERT INTO finance_profit_report_order
  (tenant_id,job_id,order_id,cohort_position,state,readiness,
   currency_code,signed_sales,signed_amazon_fees,signed_fba_fees,
   provisional_cogs,estimated_contribution,extract_sha256,captured_at)
VALUES
  ('{tenant}','{job}','{a}',0,'ELIGIBLE','ELIGIBLE_PREVIEW',
   'USD',100,-10,-5,40,45,repeat('a',64),now()),
  ('{tenant}','{job}','{b}',1,'ELIGIBLE','ELIGIBLE_MULTI_SKU_PREVIEW',
   'USD',100,-10,-5,40,45,repeat('b',64),now());
INSERT INTO finance_profit_report_sku
  (tenant_id,job_id,order_id,internal_sku,units,signed_sales,
   signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution)
VALUES
  ('{tenant}','{job}','{a}','SKU-A',1,100,-10,-5,40,45),
  ('{tenant}','{job}','{b}','SKU-B',1,60,-6,-3,24,27),
  ('{tenant}','{job}','{b}','SKU-C',1,40,-4,-2,16,18);
SAVEPOINT clean_frozen_cohort;
"""
scenarios = [
    ("baseline", f"{query};"),
    ("cancelling_per_order", f"""
UPDATE finance_profit_report_sku
  SET signed_sales=signed_sales+1,estimated_contribution=estimated_contribution+1
  WHERE order_id='{a}' AND internal_sku='SKU-A';
UPDATE finance_profit_report_sku
  SET signed_sales=signed_sales-1,estimated_contribution=estimated_contribution-1
  WHERE order_id='{b}' AND internal_sku='SKU-B';
{query};
ROLLBACK TO SAVEPOINT clean_frozen_cohort;"""),
    ("missing_eligible_sku", f"""
DELETE FROM finance_profit_report_sku WHERE order_id='{a}';
{query};
ROLLBACK TO SAVEPOINT clean_frozen_cohort;"""),
    ("ineligible_has_sku", f"""
INSERT INTO finance_profit_report_order
  (tenant_id,job_id,order_id,cohort_position,state,readiness,
   extract_sha256,captured_at)
VALUES ('{tenant}','{job}','{c}',2,'BLOCKED',
        'SOURCE_EVIDENCE_UNAVAILABLE',repeat('c',64),now());
INSERT INTO finance_profit_report_sku
  (tenant_id,job_id,order_id,internal_sku,units,signed_sales,
   signed_amazon_fees,signed_fba_fees,provisional_cogs,estimated_contribution)
VALUES ('{tenant}','{job}','{c}','SKU-INVALID',1,10,0,0,4,6);
{query};
ROLLBACK TO SAVEPOINT clean_frozen_cohort;"""),
    ("same_contribution_mismatched_components", f"""
UPDATE finance_profit_report_sku
  SET signed_sales=signed_sales+1,
      signed_amazon_fees=signed_amazon_fees-1
  WHERE order_id='{b}' AND internal_sku='SKU-B';
{query};
ROLLBACK TO SAVEPOINT clean_frozen_cohort;"""),
]
# Query outputs contain 11 aggregate fields, including the mismatch count.
sql = setup + "\n".join(fragment for _, fragment in scenarios) + "\nROLLBACK;\n"
result = subprocess.run(
    ["psql", "-h", "127.0.0.1", "-U", "merchivra", "-d", "merchivra",
     "-X", "-A", "-t", "-q", "-F", "|", "-v", "ON_ERROR_STOP=1"],
    input=sql, text=True, capture_output=True, check=False, timeout=60,
)
if result.returncode != 0:
    # Avoid printing raw psql error text, connection secrets, or full SQL.
    raise SystemExit("V192 isolated PG18 SQL execution failed; review CI job logs safely")
rows = [line.split("|") for line in result.stdout.splitlines() if "|" in line]
if len(rows) != len(scenarios) or any(len(row) != 11 for row in rows):
    raise SystemExit("V192: unexpected number or shape of exact-query PG18 aggregates")
expected = (
    ("baseline", 2, 0, 0),
    ("cancelling_per_order", 2, 0, 2),
    ("missing_eligible_sku", 2, 0, 1),
    ("ineligible_has_sku", 2, 1, 1),
    ("same_contribution_mismatched_components", 2, 0, 1),
)
for row, (name, eligible, blocked, mismatches) in zip(rows, expected, strict=True):
    if (int(row[0]), int(row[1]), int(row[5])) != (eligible, blocked, mismatches):
        raise SystemExit(f"V192: {name} did not fail closed as expected")
    if (row[6], row[7], row[8], row[9], row[10]) != (
        "200.000000", "-20.000000", "-10.000000",
        "80.000000", "90.000000"
    ):
        raise SystemExit(f"V192: {name} altered frozen V64 order totals")
print("PASS V192: real PG18 V188 SQL rejects missing/extra/cancelling SKU evidence; no writes to base tables")
