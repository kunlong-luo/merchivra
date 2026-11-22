#!/usr/bin/env python3
"""V200: execute the exact Java order-cost SQL on disposable PG18 TEMP fixtures.

Verifies whole-window mapping/cost candidate counting, not an arbitrary LIMIT 1.
Never opens Amazon credentials or modifies real/public finance or inventory.
"""
import os
from pathlib import Path
import re
import subprocess

if os.environ.get("MERCHIVRA_V200_DISPOSABLE_ONLY") != "true":
    raise SystemExit("V200 requires isolated PG18 opt-in")

java = Path("apps/server/src/main/java/org/merchivra/erp/finance/"
            "OrderCostReadinessService.java").read_text()
match = re.search(r'var items=jdbc\.query\("""\s*(.*?)\s*""",\(rs,n\)->new OrderCostEngine\.Item', java, re.S)
if not match:
    raise SystemExit("V200: cannot extract exact order cost SQL")
query = match.group(1)
if query.count("?") != 5 or "COUNT(*) OVER()" not in query:
    raise SystemExit("V200: unexpected SQL scope or missing candidate counts")
tenant = "00000000-0000-4000-8000-000000000901"
mp = "00000000-0000-4000-8000-000000000902"
order = "00000000-0000-4000-8000-000000000903"
listing = "00000000-0000-4000-8000-000000000904"
sku = "00000000-0000-4000-8000-000000000905"
sku2 = "00000000-0000-4000-8000-000000000906"
for value in ("'2026-11-02'::date", "'2026-11-02'::date",
              f"'{tenant}'::uuid", f"'{mp}'::uuid", f"'{order}'::uuid"):
    query = query.replace("?", value, 1)

commands = ["""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::integer < 180000 THEN
  RAISE EXCEPTION 'V200 requires actual PostgreSQL 18';
 END IF;
END $$;
CREATE TEMP TABLE sales_order (
 id UUID,tenant_id UUID,seller_marketplace_id UUID,order_time TIMESTAMPTZ
) ON COMMIT DROP;
CREATE TEMP TABLE order_item (
 tenant_id UUID,sales_order_id UUID,external_item_id TEXT,seller_sku_raw TEXT,
 quantity INTEGER,data_origin TEXT,is_current BOOLEAN
) ON COMMIT DROP;
CREATE TEMP TABLE marketplace_listing (
 id UUID,tenant_id UUID,seller_marketplace_id UUID,seller_sku TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE listing_sku_mapping (
 tenant_id UUID,listing_id UUID,product_sku_id UUID,
 status TEXT,valid_from TIMESTAMPTZ,valid_to TIMESTAMPTZ,mapping_version INTEGER
) ON COMMIT DROP;
CREATE TEMP TABLE product_sku (
 id UUID,tenant_id UUID,internal_sku TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE sku_cost_version (
 tenant_id UUID,product_sku_id UUID,effective_from DATE,effective_to DATE,
 unit_cost NUMERIC(20,6),currency_code CHAR(3)
) ON COMMIT DROP;
"""]
commands.append(f"""
INSERT INTO sales_order VALUES ('{order}','{tenant}','{mp}','2026-11-02T09:00:00Z');
INSERT INTO order_item VALUES
 ('{tenant}','{order}','ITEM-001','SELLER-SKU',2,'LIVE',true);
INSERT INTO marketplace_listing VALUES ('{listing}','{tenant}','{mp}','SELLER-SKU');
INSERT INTO product_sku VALUES
 ('{sku}','{tenant}','INTERNAL-SKU-1'),
 ('{sku2}','{tenant}','INTERNAL-SKU-2');
INSERT INTO listing_sku_mapping VALUES
 ('{tenant}','{listing}','{sku}','ACTIVE',
  '2026-10-01T00:00:00Z',NULL,1);
INSERT INTO sku_cost_version VALUES
 ('{tenant}','{sku}','2026-10-01',NULL,8.250000,'USD');
CREATE TEMP VIEW v200_cost_readiness AS
{query};
""")

def check(name, condition):
    if not re.fullmatch(r"[A-Za-z0-9 _-]{1,70}", name):
        raise ValueError("Unsafe test label")
    commands.append(f"""
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM v200_cost_readiness WHERE {condition}) THEN
  RAISE EXCEPTION 'V200 PG18 failed: {name}';
 END IF;
END $$;
""")

check("clean unique mapping and cost",
      "mapping_candidates=1 AND cost_candidates=1 "
      "AND internal_sku='INTERNAL-SKU-1' AND unit_cost=8.250000")
commands.append(f"""
-- V19 permits these OVERLAPPING *closed and open* effective windows.
-- A most-recent-wins LIMIT 1 would falsely declare the cost unambiguous.
UPDATE sku_cost_version SET effective_to='2026-12-01'
 WHERE product_sku_id='{sku}';
INSERT INTO sku_cost_version VALUES
 ('{tenant}','{sku}','2026-10-15',NULL,8.250000,'USD');
""")
check("overlap despite equal historical unit costs",
      "mapping_candidates=1 AND cost_candidates=2 "
      "AND unit_cost IS NULL AND currency_code IS NULL")
commands.append(f"""
DELETE FROM sku_cost_version
 WHERE product_sku_id='{sku}' AND effective_from='2026-10-15';
UPDATE sku_cost_version SET effective_to=NULL
 WHERE product_sku_id='{sku}';
-- Two historical mappings to DIFFERENT internal SKUs at the same order time.
INSERT INTO listing_sku_mapping VALUES
 ('{tenant}','{listing}','{sku2}','SUPERSEDED',
  '2026-10-15T00:00:00Z',NULL,2);
""")
check("ambiguous historical listing sku mapping",
      "mapping_candidates=2 AND internal_sku IS NULL AND unit_cost IS NULL")
commands.append(f"""
DELETE FROM listing_sku_mapping
 WHERE product_sku_id='{sku2}';
-- A missing cost is distinct from two candidate costs.
DELETE FROM sku_cost_version WHERE product_sku_id='{sku}';
""")
check("missing history never produces a selected cost",
      "mapping_candidates=1 AND cost_candidates IS NULL AND unit_cost IS NULL")
commands.append("ROLLBACK;\n")
p=subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
                  "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
                 input="".join(commands),capture_output=True,text=True,
                 timeout=100,check=False)
if p.returncode:
    raise SystemExit("V200 real PG18 TEMP test failed:\n"+p.stderr[-3500:])
print("PASS V200 PG18 exact Java SQL: unique, overlapping cost, "
      "ambiguous mapping, missing history, all rollback-only")
