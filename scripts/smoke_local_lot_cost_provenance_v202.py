#!/usr/bin/env python3
"""V202: run EXACT Java batch provenance SQL against PG18 TEMP/ROLLBACK data.

This checks receipt/PO-line/lot/QC/ledger identity and currency ambiguity.
No sale-to-lot assignment, accounting posting, production data or Amazon APIs.
"""
import os
from pathlib import Path
import re
import subprocess

if os.getenv("MERCHIVRA_V202_DISPOSABLE_ONLY") != "true":
    raise SystemExit("V202 requires disposable PG18 opt-in")
source=Path("apps/server/src/main/java/org/merchivra/erp/finance/"
            "OrderLotProvenanceServiceV202.java").read_text()
m=re.search(r'static final String RECEIPT_LINEAGE_SQL\s*=\s*"""\s*(.*?)\s*""";',
            source,re.S)
if not m:
    raise SystemExit("V202 cannot locate original Java SQL")
sql=m.group(1)
if sql.count("?")!=2 or sql.count("%s")!=1:
    raise SystemExit("V202 changed original SQL parameters: audit the fixture")
tenant="00000000-0000-4000-8000-00000000b201"
other="00000000-0000-4000-8000-00000000b202"
sku="00000000-0000-4000-8000-00000000b203"
sku2="00000000-0000-4000-8000-00000000b204"
warehouse="00000000-0000-4000-8000-00000000b205"
po="00000000-0000-4000-8000-00000000b206"
line="00000000-0000-4000-8000-00000000b207"
receipt="00000000-0000-4000-8000-00000000b208"
inspection="00000000-0000-4000-8000-00000000b209"
po2="00000000-0000-4000-8000-00000000b20a"
line2="00000000-0000-4000-8000-00000000b20b"
receipt2="00000000-0000-4000-8000-00000000b20c"
future="00000000-0000-4000-8000-00000000b20d"
sql=sql.replace("?",f"'{tenant}'::uuid",1)
sql=sql.replace("?","'2026-11-02T09:00:00Z'::timestamptz",1)
sql=sql.replace("%s","'SKU-A'",1)

commands=[r"""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::int < 180000 THEN
  RAISE EXCEPTION 'V202 needs real PostgreSQL 18';
 END IF;
END $$;
CREATE TEMP TABLE product_sku (
 id UUID,tenant_id UUID,internal_sku TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_order (
 id UUID,tenant_id UUID,currency_code CHAR(3)
) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_order_line (
 id UUID,tenant_id UUID,purchase_order_id UUID,product_sku_id UUID,
 unit_cost NUMERIC(20,6)
) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_receipt (
 id UUID,tenant_id UUID,purchase_order_id UUID,purchase_order_line_id UUID,
 quantity INTEGER,warehouse_id UUID,received_at TIMESTAMPTZ
) ON COMMIT DROP;
CREATE TEMP TABLE erp_receipt_quality_batch (
 tenant_id UUID,receipt_id UUID,product_sku_id UUID,warehouse_id UUID,
 received_quantity INTEGER,status TEXT,inspection_id UUID
) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_receipt_qc (
 id UUID,tenant_id UUID,purchase_receipt_id UUID,product_sku_id UUID,
 warehouse_id UUID,inspected_quantity INTEGER,accepted_quantity INTEGER,
 rejected_quantity INTEGER
) ON COMMIT DROP;
CREATE TEMP TABLE erp_local_stock_movement (
 tenant_id UUID,inspection_id UUID,movement_type TEXT,quantity INTEGER,
 product_sku_id UUID,warehouse_id UUID
) ON COMMIT DROP;
"""]
commands.append(f"CREATE TEMP VIEW v202_local_lot AS\n{sql};\n")

def check(label,condition):
    if not re.fullmatch(r"[A-Za-z0-9 _-]{1,80}",label):
        raise ValueError("Unsafe label")
    commands.append(f"""
DO $$ BEGIN
 IF NOT ({condition}) THEN RAISE EXCEPTION 'V202 test failed: {label}';
 END IF;
END $$;
""")

check("no matching local receipt is not a zero cost",
      "(SELECT count(*) FROM v202_local_lot)=0")
commands.append(f"""
INSERT INTO product_sku VALUES ('{sku}','{tenant}','SKU-A'),
  ('{sku2}','{tenant}','SKU-B'),
  ('{sku}','{other}','SKU-A');
INSERT INTO erp_purchase_order VALUES
 ('{po}','{tenant}','USD'),('{po2}','{tenant}','EUR'),
 ('{po}','{other}','USD');
INSERT INTO erp_purchase_order_line VALUES
 ('{line}','{tenant}','{po}','{sku}',4.250000),
 ('{line2}','{tenant}','{po2}','{sku}',6.500000),
 ('{line}','{other}','{po}','{sku}',99.000000);
INSERT INTO erp_purchase_receipt VALUES
 ('{receipt}','{tenant}','{po}','{line}',3,'{warehouse}',
  '2026-10-20T00:00:00Z'),
 ('{receipt}','{other}','{po}','{line}',500,'{warehouse}',
  '2026-10-20T00:00:00Z'),
 ('{future}','{tenant}','{po}','{line}',9,'{warehouse}',
  '2026-12-01T00:00:00Z');
""")
check("legacy receipt counted once scoped prior order",
      "(SELECT receipt_count=1 AND legacy_count=1 AND currency_count=1 "
      "AND cost_count=1 AND min_unit_cost=4.250000 "
      "FROM v202_local_lot WHERE internal_sku='SKU-A')")
commands.append(f"""
INSERT INTO erp_receipt_quality_batch VALUES
 ('{tenant}','{receipt}','{sku}','{warehouse}',3,'PENDING_QC',NULL);
""")
check("pending QC cannot be released",
      "(SELECT receipt_count=1 AND legacy_count=0 AND pending_count=1 "
      "AND released_count=0 AND observed_local_released_units=0 "
      "FROM v202_local_lot WHERE internal_sku='SKU-A')")
commands.append(f"""
INSERT INTO erp_purchase_receipt_qc VALUES
 ('{inspection}','{tenant}','{receipt}','{sku}','{warehouse}',3,2,1);
UPDATE erp_receipt_quality_batch SET status='INSPECTED',
 inspection_id='{inspection}' WHERE tenant_id='{tenant}';
""")
check("inspected unreleased goods excluded",
      "(SELECT inspected_count=1 AND released_count=0 "
      "AND qc_conflicts=0 AND observed_local_released_units=0 FROM v202_local_lot)")
commands.append(f"""
UPDATE erp_receipt_quality_batch SET status='RELEASED'
 WHERE tenant_id='{tenant}';
INSERT INTO erp_local_stock_movement VALUES
 ('{tenant}','{inspection}','QC_RELEASE',2,'{sku}','{warehouse}');
""")
check("valid local release is still only local observation",
      "(SELECT released_count=1 AND observed_local_released_units=2 "
      "AND receipt_conflicts=0 AND qc_conflicts=0 AND ledger_conflicts=0 "
      "FROM v202_local_lot)")
commands.append(f"""
UPDATE erp_purchase_receipt_qc SET purchase_receipt_id='{future}'
 WHERE tenant_id='{tenant}';
""")
check("wrong QC source receipt cannot contribute",
      "(SELECT qc_conflicts=1 AND observed_local_released_units=0 "
      "FROM v202_local_lot)")
commands.append(f"""
UPDATE erp_purchase_receipt_qc SET purchase_receipt_id='{receipt}'
 WHERE tenant_id='{tenant}';
UPDATE erp_receipt_quality_batch SET product_sku_id='{sku2}'
 WHERE tenant_id='{tenant}';
""")
check("wrong lot sku cannot contribute",
      "(SELECT receipt_conflicts=1 AND observed_local_released_units=0 "
      "FROM v202_local_lot)")
commands.append(f"""
UPDATE erp_receipt_quality_batch SET product_sku_id='{sku}'
 WHERE tenant_id='{tenant}';
UPDATE erp_local_stock_movement SET quantity=1 WHERE tenant_id='{tenant}';
""")
check("short credited stock release cannot contribute",
      "(SELECT ledger_conflicts=1 AND observed_local_released_units=0 "
      "FROM v202_local_lot)")
commands.append(f"""
UPDATE erp_local_stock_movement SET quantity=2 WHERE tenant_id='{tenant}';
INSERT INTO erp_purchase_receipt VALUES
 ('{receipt2}','{tenant}','{po2}','{line2}',1,'{warehouse}',
  '2026-10-21T00:00:00Z');
INSERT INTO erp_receipt_quality_batch VALUES
 ('{tenant}','{receipt2}','{sku}','{warehouse}',1,'PENDING_QC',NULL);
""")
check("multiple po currencies and unit costs stay ambiguous",
      "(SELECT receipt_count=2 AND currency_count=2 AND cost_count=2 "
      "AND released_count=1 AND observed_local_released_units=2 "
      "FROM v202_local_lot)")
commands.append("ROLLBACK;\n")
proc=subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
                     "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
                    input="".join(commands),capture_output=True,text=True,
                    timeout=100,check=False)
if proc.returncode:
    raise SystemExit("V202 PG18 exact SQL failed:\n"+proc.stderr[-4000:])
print("PASS V202 exact Java SQL on PG18 TEMP: tenant/time isolation, "
      "legacy/QC/release controls, identity conflicts and mixed PO money; rollback-only")
