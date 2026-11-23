#!/usr/bin/env python3
"""V212 PG18 source-exact SELECT scope checks, synthetic temporary fixtures.

The V106 recomputation VIEW is represented by a temporary *shadow* only
here; the real V106 trigger and view have separate existing V203 regressions.
This verifies the V212 SQL boundaries, not original lot authenticity/COGS.
"""
import os
import re
import subprocess
from pathlib import Path

if os.environ.get("MERCHIVRA_V212_DISPOSABLE_ONLY")!="true":
    raise SystemExit("V212 needs explicitly disposable PostgreSQL 18 opt-in")

java=Path("apps/server/src/main/java/org/merchivra/erp/finance/"
          "RefundBatchLineageServiceV212.java").read_text()

def exact(name,params):
    anchor='static final String '+name+'="""\n'
    if java.count(anchor)!=1:
        raise SystemExit("Missing V212 Java SQL literal "+name)
    sql=java.split(anchor,1)[1].split('\n        """;',1)[0].strip()
    if sql.count("?")!=len(params) or not sql.endswith("LIMIT 1001"):
        raise SystemExit("V212 unbounded or changed source SQL "+name)
    for p in params:
        sql=sql.replace("?","'"+p+"'",1)
    if "?" in sql:
        raise SystemExit("Unexpected unbound source SQL")
    return sql

def uid(n):
    return "00000000-0000-4000-8000-"+f"{n:012x}"
ids={s:uid(0xE2120+i) for i,s in enumerate((
    "tenant","foreign","mp","other_mp","order","other_order",
    "sale_claim1","sale_claim2","sale_claim3","receipt1","receipt2",
    "event1","event2","event3","manual_claim1","manual_claim2",
    "intake1","intake2","intake3","foreign_intake","foreign_claim"))}
review=exact("REFUND_ITEM_REVIEWS_SQL",[ids["tenant"],ids["order"]])
claims=exact("ORIGINAL_LOT_CLAIMS_SQL",
             [ids["tenant"],ids["mp"],ids["order"]])
intakes=exact("RETURN_INTAKES_SQL",
              [ids["tenant"],ids["mp"],ids["order"]])
sql="""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::int<180000 THEN
  RAISE EXCEPTION 'V212 must be checked on real PostgreSQL 18';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history
    WHERE version='111' AND success) THEN
  RAISE EXCEPTION 'V212 depends on applied V106/V96/V97 Flyway through V111';
 END IF;
END $$;
CREATE TEMP TABLE finance_refund_item_review(
 tenant_id UUID,sales_order_id UUID,event_id UUID,external_item_id TEXT,
 reviewed_quantity INT,disposition TEXT) ON COMMIT DROP;
CREATE TEMP TABLE finance_sale_lot_claim_v106(
 id UUID,tenant_id UUID,marketplace_id UUID,sales_order_id UUID,receipt_id UUID,
 snapshot_external_item_id TEXT,assigned_quantity INT,
 snapshot_currency CHAR(3),snapshot_unit_cost NUMERIC(20,6),
 external_payload_sha256 CHAR(64)) ON COMMIT DROP;
-- Shadow only in this source-scoping test; real V106 PG recomputation is
-- already covered in the independent V203 Flyway smoke, not by this fixture.
CREATE TEMP TABLE finance_sale_lot_integrity_v106(
 tenant_id UUID,claim_id UUID,integrity_state TEXT) ON COMMIT DROP;
CREATE TEMP TABLE finance_return_receipt_claim_v96(
 id UUID,tenant_id UUID,event_id UUID,sales_order_id UUID,
 external_item_id TEXT,claimed_quantity INT,condition_code TEXT,state TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_customer_return_intake_v97(
 id UUID,claim_id UUID,event_id UUID,external_item_id TEXT,
 received_quantity INT,state TEXT,condition_code TEXT,
 tenant_id UUID,seller_marketplace_id UUID,sales_order_id UUID
) ON COMMIT DROP;

INSERT INTO finance_refund_item_review VALUES
 ('@tenant@','@order@','@event1@','ITEM-A',2,'RESTOCKED_CLAIMED'),
 ('@tenant@','@order@','@event2@','ITEM-A',1,'NOT_RESTOCKED'),
 ('@tenant@','@other_order@','@event3@','ITEM-A',88,'RESTOCKED_CLAIMED'),
 ('@foreign@','@order@','@event3@','ITEM-A',99,'RESTOCKED_CLAIMED');

INSERT INTO finance_sale_lot_claim_v106 VALUES
 ('@sale_claim1@','@tenant@','@mp@','@order@','@receipt1@',
  'ITEM-A',2,'USD',6.50,repeat('a',64)),
 ('@sale_claim2@','@tenant@','@mp@','@order@','@receipt2@',
  'ITEM-A',1,'EUR',9.25,repeat('b',64)),
 ('@sale_claim3@','@tenant@','@mp@','@other_order@','@receipt1@',
  'ITEM-A',300,'USD',6.50,repeat('c',64)),
 ('@foreign_claim@','@foreign@','@mp@','@order@','@receipt1@',
  'ITEM-A',200,'USD',6.50,repeat('d',64));
INSERT INTO finance_sale_lot_integrity_v106 VALUES
 ('@tenant@','@sale_claim1@','LOCAL_LINK_INTACT_UNVERIFIED'),
 ('@tenant@','@sale_claim2@','LOCAL_LINK_INTACT_UNVERIFIED'),
 ('@tenant@','@sale_claim3@','LOCAL_LINK_INTACT_UNVERIFIED');

INSERT INTO finance_return_receipt_claim_v96 VALUES
 ('@manual_claim1@','@tenant@','@event1@','@order@',
  'ITEM-A',2,'SELLABLE_CLAIMED','REVIEWED_MANUAL'),
 ('@manual_claim2@','@tenant@','@event2@','@other_order@',
  'ITEM-A',1,'SELLABLE_CLAIMED','REVIEWED_MANUAL');
INSERT INTO finance_customer_return_intake_v97 VALUES
 ('@intake1@','@manual_claim1@','@event1@','ITEM-A',
  1,'ATTESTED_LOCAL','SELLABLE_CLAIMED','@tenant@','@mp@','@order@'),
 ('@intake2@','@manual_claim1@','@event1@','ITEM-A',
  1,'PENDING','SELLABLE_CLAIMED','@tenant@','@mp@','@order@'),
 ('@intake3@','@manual_claim2@','@event2@','ITEM-A',
  1,'ATTESTED_LOCAL','SELLABLE_CLAIMED','@tenant@','@mp@','@order@'),
 ('@foreign_intake@','@manual_claim1@','@event1@','ITEM-A',
  200,'ATTESTED_LOCAL','SELLABLE_CLAIMED','@foreign@','@mp@','@order@');

CREATE TEMP TABLE v212_review AS
"""
sql+=review+";\nCREATE TEMP TABLE v212_claims AS\n"+claims+";\n"
sql+="CREATE TEMP TABLE v212_intakes AS\n"+intakes+";\n"
sql+="""
DO $$ BEGIN
 IF (SELECT count(*) FROM v212_review)<>2
 OR (SELECT sum(reviewed_quantity) FROM v212_review)<>3 THEN
  RAISE EXCEPTION 'V212 refund review must read ALL V34 rows in one order';
 END IF;
 IF (SELECT count(*) FROM v212_claims)<>2
 OR (SELECT sum(assigned_quantity) FROM v212_claims)<>3
 OR (SELECT count(DISTINCT receipt_id) FROM v212_claims)<>2 THEN
  RAISE EXCEPTION 'V212 original V106 lots lost, duplicated or scope leaked';
 END IF;
 IF (SELECT count(*) FROM v212_intakes)<>3
 OR (SELECT count(*) FROM v212_intakes WHERE receipt_claim_state IS NULL)<>1
 OR (SELECT coalesce(sum(received_quantity),0) FROM v212_intakes
     WHERE state='ATTESTED_LOCAL' AND receipt_claim_state='REVIEWED_MANUAL')<>1
 THEN
  RAISE EXCEPTION 'V212 intake manual source must not be silently inferred';
 END IF;
END $$;
UPDATE finance_sale_lot_integrity_v106 SET integrity_state='RETRACTED'
 WHERE claim_id='@sale_claim2@' AND tenant_id='@tenant@';
CREATE TEMP TABLE v212_recheck AS
"""
sql+=claims+";\n"
sql+="""
DO $$ BEGIN
 IF (SELECT count(*) FROM v212_recheck WHERE integrity_state='RETRACTED')<>1
 THEN
  RAISE EXCEPTION 'V212 recomputed read-time claim state not reflected';
 END IF;
END $$;
ROLLBACK;
"""
for k,v in ids.items():
    sql=sql.replace("@"+k+"@",v)
if re.search(r"@[a-z0-9_]+@",sql):
    raise SystemExit("V212 fixture UUID substitution failed")
p=subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
                  "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
                 input=sql,text=True,capture_output=True,timeout=120)
if p.returncode:
    raise SystemExit("V212 real PG18 bounded original SQL: "+p.stderr[-6500:])
print("PASS V212 PG18 exact V34/V106/V96/V97 source SELECT scope: "
      "original sale lots 2+1 not multiplied, USD/EUR purchase references, "
      "independently reviewed return intake vs pending and broken manual "
      "claim, tenant/marketplace/order isolation, changed V106 identity "
      "visible on reread; TEMP shadow V106 view ONLY, ROLLBACK")
