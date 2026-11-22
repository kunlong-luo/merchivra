#!/usr/bin/env python3
"""V201: execute the exact Java V34 cross-event quantity SQL on disposable PG18.

All rows live in TEMP tables within a transaction. Never touches tenant,
Amazon, financial ledger, or public inventory data.
"""
import os
from pathlib import Path
import re
import subprocess

if os.environ.get("MERCHIVRA_V201_DISPOSABLE_ONLY") != "true":
    raise SystemExit("V201 needs explicit disposable PostgreSQL18 opt-in")

java=Path("apps/server/src/main/java/org/merchivra/erp/finance/"
          "RefundItemReviewService.java").read_text()
match=re.search(r'static final String QUANTITY_CONSERVATION_SQL\s*=\s*"""\s*(.*?)\s*""";',
                java,re.S)
if not match:
    raise SystemExit("Cannot find original V201 Java SQL")
query=match.group(1)
if query.count("?")!=2 or "SUM(r.reviewed_quantity)" not in query:
    raise SystemExit("Unexpected scoped V201 SQL")
tenant="00000000-0000-4000-8000-000000000a01"
other_tenant="00000000-0000-4000-8000-000000000a02"
order="00000000-0000-4000-8000-000000000a03"
other_order="00000000-0000-4000-8000-000000000a04"
event_a="00000000-0000-4000-8000-000000000a05"
event_b="00000000-0000-4000-8000-000000000a06"
event_c="00000000-0000-4000-8000-000000000a07"
query=query.replace("?",f"'{tenant}'::uuid",1).replace("?",f"'{order}'::uuid",1)

commands=[r"""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::integer < 180000 THEN
  RAISE EXCEPTION 'V201 needs real PG18';
 END IF;
END $$;
CREATE TEMP TABLE order_item (
 tenant_id UUID,sales_order_id UUID,external_item_id TEXT,
 quantity INTEGER,data_origin TEXT,is_current BOOLEAN
) ON COMMIT DROP;
CREATE TEMP TABLE finance_refund_item_review (
 tenant_id UUID,sales_order_id UUID,event_id UUID,
 external_item_id TEXT,reviewed_quantity INTEGER
) ON COMMIT DROP;
"""]
commands.append(f"""
INSERT INTO order_item VALUES
 ('{tenant}','{order}','ITEM-1',3,'LIVE',true),
 ('{tenant}','{order}','ITEM-2',2,'LIVE',true),
 ('{tenant}','{other_order}','ITEM-1',1,'LIVE',true),
 ('{other_tenant}','{order}','ITEM-1',1,'LIVE',true);
INSERT INTO finance_refund_item_review VALUES
 ('{tenant}','{order}','{event_a}','ITEM-1',2),
 ('{tenant}','{order}','{event_b}','ITEM-1',1),
 ('{tenant}','{order}','{event_a}','ITEM-2',1),
 ('{tenant}','{other_order}','{event_c}','ITEM-1',1),
 ('{other_tenant}','{order}','{event_c}','ITEM-1',1);
CREATE TEMP VIEW v201_quantity_conflicts AS
{query};
""")

def check(name,condition):
    if not re.fullmatch(r"[A-Za-z0-9 _-]{1,80}",name):
        raise ValueError("Unsafe fixture name")
    commands.append(f"""
DO $$ BEGIN
 IF NOT ({condition}) THEN
   RAISE EXCEPTION 'V201 source SQL failed: {name}';
 END IF;
END $$;
""")

check("balanced multi event initial review",
      "(SELECT count(*) FROM v201_quantity_conflicts)=0")
commands.append(f"""
-- Historical LIVE order quantity changed after both reviews, each event
-- individually still reports 2 or 1, while combined 3 exceeds current 2.
UPDATE order_item SET quantity=2
 WHERE tenant_id='{tenant}' AND sales_order_id='{order}'
 AND external_item_id='ITEM-1';
""")
check("late order quantity decrease invalidates old reviews",
      "(SELECT count(*) FROM v201_quantity_conflicts)=1 "
      "AND (SELECT external_item_id FROM v201_quantity_conflicts)='ITEM-1'")
commands.append(f"""
-- Correcting one old event returns to a safe sum.  Other tenants and orders
-- must never be aggregated into this order's claim.
DELETE FROM finance_refund_item_review
 WHERE tenant_id='{tenant}' AND sales_order_id='{order}'
 AND event_id='{event_b}' AND external_item_id='ITEM-1';
""")
check("other tenant order and event do not leak",
      "(SELECT count(*) FROM v201_quantity_conflicts)=0")
commands.append(f"""
-- Even when an order item exists as a DB row, a deactivated/old source line
-- cannot serve as current LIVE quantity proof for either refund event.
UPDATE order_item SET is_current=false WHERE tenant_id='{tenant}'
 AND sales_order_id='{order}' AND external_item_id='ITEM-1';
""")
check("not current line cannot validate quantity",
      "(SELECT count(*) FROM v201_quantity_conflicts)=1 "
      "AND (SELECT external_item_id FROM v201_quantity_conflicts)='ITEM-1'")
commands.append(f"""
UPDATE order_item SET is_current=true,data_origin='MOCK'
 WHERE tenant_id='{tenant}' AND sales_order_id='{order}'
 AND external_item_id='ITEM-1';
""")
check("non live item cannot validate quantity",
      "(SELECT count(*) FROM v201_quantity_conflicts)=1")
commands.append(f"""
UPDATE order_item SET data_origin='LIVE',quantity=3
 WHERE tenant_id='{tenant}' AND sales_order_id='{order}'
 AND external_item_id='ITEM-1';
-- An entirely different item in the same order must be checked as well.
UPDATE finance_refund_item_review SET reviewed_quantity=3
 WHERE tenant_id='{tenant}' AND sales_order_id='{order}'
 AND external_item_id='ITEM-2';
""")
check("other item cross event conflict visible",
      "(SELECT count(*) FROM v201_quantity_conflicts)=1 "
      "AND (SELECT external_item_id FROM v201_quantity_conflicts)='ITEM-2'")
commands.append("ROLLBACK;\n")
res=subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
                    "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
                   input="".join(commands),capture_output=True,text=True,
                   timeout=100,check=False)
if res.returncode:
    raise SystemExit("V201 real PG18 source SQL regression failed:\n"+res.stderr[-4000:])
print("PASS V201 exact Java SQL: cross-event quantity sum, late order "
      "change, current LIVE item, other tenant/order, all rollback-only")
