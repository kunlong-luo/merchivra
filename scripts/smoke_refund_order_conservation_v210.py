#!/usr/bin/env python3
"""V210 PG18 synthetic SQL contract: exactly all matched LIVE order refunds.

Source-exact Java SQL, TEMP tables, ROLLBACK. Not a test of Amazon data, stock
or accounting policy. Engine's signed-money + quantity proofs run in Java.
"""
import os
import re
import subprocess
from pathlib import Path

if os.environ.get("MERCHIVRA_V210_DISPOSABLE_ONLY")!="true":
    raise SystemExit("V210 requires explicit disposable PG18 opt-in")

java=Path("apps/server/src/main/java/org/merchivra/erp/finance/"
          "RefundOrderConservationServiceV210.java").read_text()
def source_sql(name,values):
    prefix='static final String '+name+' = """\n'
    if java.count(prefix)!=1:
        raise SystemExit("Missing V210 source-exact SQL "+name)
    sql=java.split(prefix,1)[1].split('\n        """;',1)[0].strip()
    if sql.count("?")!=len(values):
        raise SystemExit("Unexpected SQL bind parameter count for "+name)
    if not sql.endswith("LIMIT 1001"):
        raise SystemExit("Full-scope SQL must explicitly enforce limit")
    for value in values:
        sql=sql.replace("?","'"+value+"'",1)
    if re.search(r"\?",sql):
        raise SystemExit("Unsubstituted SQL parameter")
    return sql

def uid(n):
    return "00000000-0000-4000-8000-"+f"{n:012x}"
tenant=uid(0xE210)
foreign=uid(0xE211)
mp=uid(0xE212)
order=uid(0xE213)
other=uid(0xE214)
event1=uid(0xE215)
event2=uid(0xE216)
unmatched=uid(0xE217)
other_event=uid(0xE218)

sources=source_sql("MATCHED_REFUNDS_SQL",[tenant,mp,order])
reviewed=source_sql("ALL_ORDER_MANUAL_REVIEWS_SQL",[tenant,order])
items=source_sql("ALL_LIVE_ORDER_ITEMS_SQL",[tenant,order])

sql="""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::int<180000 THEN
  RAISE EXCEPTION 'V210 requires PostgreSQL 18';
 END IF;
END $$;
CREATE TEMP TABLE finance_source_event (
 id UUID,tenant_id UUID,seller_marketplace_id UUID,posted_at TIMESTAMPTZ,
 currency_code CHAR(3),amount NUMERIC(20,6),source_family TEXT,
 data_origin TEXT,category TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_event_link_review (
 tenant_id UUID,event_id UUID,order_id UUID,state TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_refund_item_review (
 tenant_id UUID,sales_order_id UUID,event_id UUID,
 external_item_id TEXT,reviewed_quantity INTEGER,disposition TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE order_item (
 tenant_id UUID,sales_order_id UUID,external_item_id TEXT,
 quantity INTEGER,is_current BOOLEAN,data_origin TEXT
) ON COMMIT DROP;
INSERT INTO order_item VALUES
 ('@tenant@','@order@','ITEM-A',5,TRUE,'LIVE'),
 ('@tenant@','@order@','ITEM-A',999,FALSE,'LIVE'),
 ('@tenant@','@order@','ITEM-A',999,TRUE,'SYNTHETIC'),
 ('@tenant@','@other@','ITEM-B',300,TRUE,'LIVE');
INSERT INTO finance_source_event VALUES
 ('@event1@','@tenant@','@mp@','2026-11-01T10:00:00Z',
   'USD',-20,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@event2@','@tenant@','@mp@','2026-11-03T11:00:00Z',
   'USD',-10,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@unmatched@','@tenant@','@mp@','2026-11-04T11:00:00Z',
   'USD',-50,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@other_event@','@tenant@','@mp@','2026-11-05T11:00:00Z',
   'USD',-100,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND'),
 ('@foreign_event@','@foreign@','@mp@','2026-11-06T11:00:00Z',
   'USD',-200,'SPAPI_FINANCES','LIVE','PRODUCT_REFUND');
INSERT INTO finance_event_link_review VALUES
 ('@tenant@','@event1@','@order@','MATCHED'),
 ('@tenant@','@event2@','@order@','MATCHED'),
 ('@tenant@','@other_event@','@other@','MATCHED'),
 ('@foreign@','@foreign_event@','@order@','MATCHED');
INSERT INTO finance_refund_item_review VALUES
 ('@tenant@','@order@','@event1@','ITEM-A',2,'RESTOCKED_CLAIMED'),
 ('@tenant@','@order@','@event2@','ITEM-A',1,'NOT_RESTOCKED'),
 ('@tenant@','@other@','@other_event@','ITEM-B',300,'NOT_RESTOCKED'),
 ('@foreign@','@order@','@foreign_event@','ITEM-A',99,'RESTOCKED_CLAIMED');

CREATE TEMP TABLE v210_sources AS
"""
sql+=sources+";\n"
sql+="CREATE TEMP TABLE v210_sale_items AS\n"+items+";\n"
sql+="CREATE TEMP TABLE v210_reviews AS\n"+reviewed+";\n"
sql+="""
DO $$ BEGIN
 IF (SELECT count(*) FROM v210_sources)<>2 THEN
   RAISE EXCEPTION 'V210 matched source scope leaked another order or tenant';
 END IF;
 IF (SELECT coalesce(sum(amount),0) FROM v210_sources)<>-30 THEN
   RAISE EXCEPTION 'V210 source amounts amplified or lost';
 END IF;
 IF (SELECT count(*) FROM v210_reviews)<>2
   OR (SELECT sum(reviewed_quantity) FROM v210_reviews)<>3 THEN
   RAISE EXCEPTION 'V210 missing second manually reviewed refund event';
 END IF;
 IF (SELECT count(*) FROM v210_sale_items)<>1
   OR (SELECT quantity FROM v210_sale_items)<>5 THEN
   RAISE EXCEPTION 'V210 old/synthetic item improperly used as LIVE sale quantity';
 END IF;
END $$;
-- Verify a current order quantity revision is visible, not silently
-- replaced with previously frozen quantities.
UPDATE order_item SET quantity=2
 WHERE tenant_id='@tenant@' AND sales_order_id='@order@'
   AND external_item_id='ITEM-A' AND is_current=TRUE AND data_origin='LIVE';
CREATE TEMP TABLE v210_revised_items AS
"""
sql+=items+";\n"
sql+="""
DO $$ BEGIN
 IF (SELECT quantity FROM v210_revised_items)<>2
    OR (SELECT sum(reviewed_quantity) FROM v210_reviews)<>3 THEN
  RAISE EXCEPTION 'V210 cross-event quantity revision no longer detectable';
 END IF;
END $$;
ROLLBACK;
"""
mapping={
 "tenant":tenant,"foreign":foreign,"mp":mp,"order":order,
 "other":other,"event1":event1,"event2":event2,
 "unmatched":unmatched,"other_event":other_event,
 "foreign_event":uid(0xE219)
}
for k,v in mapping.items():
    sql=sql.replace("@"+k+"@",v)
if re.search(r"@[a-z0-9_]+@",sql):
    raise SystemExit("V210 fixture placeholder not substituted")
proc=subprocess.run([
 "psql","-X","-h","127.0.0.1","-U","merchivra",
 "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
 input=sql,text=True,capture_output=True,timeout=120)
if proc.returncode:
    raise SystemExit("V210 source-exact PG18 SQL regression: "+proc.stderr[-4500:])
print("PASS V210 PG18 exact full-order LIVE refund source SQL: "
      "two partial refunds, signed amount no multiplication, all V34 quantity "
      "rows, isolated tenant/order, old/synthetic quantity excluded, "
      "current quantity revision observable; TEMP/ROLLBACK synthetic")
