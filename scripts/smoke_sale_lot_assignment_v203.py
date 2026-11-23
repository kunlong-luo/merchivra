#!/usr/bin/env python3
"""V203: source-exact V106 trigger and view, disposable PG18 TEMP/ROLLBACK only.

Uses the *installed* Flyway function and *exact* V106 SELECT view with shadow
tables. No production rows, Seller API, external signatures, cost or posting.
"""
import os
from pathlib import Path
import subprocess

if os.getenv("MERCHIVRA_V203_DISPOSABLE_ONLY") != "true":
    raise SystemExit("V203 requires explicit disposable PG18 opt-in")
migration=Path("apps/server/src/main/resources/db/migration/"
               "V106__sale_lot_assignment_claim_integrity.sql").read_text()
needle="CREATE VIEW finance_sale_lot_integrity_v106 AS\n"
if migration.count(needle)!=1:
    raise SystemExit("V203 exact V106 integrity view not found")
view=migration.split(needle,1)[1].split("\nCOMMENT ON VIEW",1)[0]
if not view.rstrip().endswith(";"):
    raise SystemExit("V203 integrity view truncated")
def uid(i):
    return f"00000000-0000-4000-8000-00000000c{i:03x}"
tenant,mp,order=uid(301),uid(302),uid(303)
item,op,pick,line,pos,receipt=map(uid,[304,305,306,307,308,309])
inspection,po,pol,sku,warehouse,actor=map(uid,[310,311,312,313,314,315])
claim,item2,op2,pick2,line2,pos2,claim2=map(uid,[316,317,318,319,320,321,322])
hashval="a"*64

sql=f"""
BEGIN;
DO $$ BEGIN
  IF current_setting('server_version_num')::int<180000 THEN
    RAISE EXCEPTION 'V203 needs PostgreSQL 18';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.flyway_schema_history
       WHERE version='106' AND success) THEN
    RAISE EXCEPTION 'Flyway V106 has not run';
  END IF;
END $$;
CREATE TEMP TABLE sales_order (
 id uuid,tenant_id uuid,seller_marketplace_id uuid,data_origin text,
 source_updated_at timestamptz
) ON COMMIT DROP;
CREATE TEMP TABLE order_item (
 id uuid,tenant_id uuid,sales_order_id uuid,quantity integer,
 external_item_id text,is_current boolean,source_updated_at timestamptz
) ON COMMIT DROP;
CREATE TEMP TABLE erp_local_lot_pick_line (
 id uuid,tenant_id uuid,pick_id uuid,position_id uuid,quantity integer
) ON COMMIT DROP;
CREATE TEMP TABLE erp_local_lot_pick (
 id uuid,tenant_id uuid,operation_id uuid,quantity integer,
 product_sku_id uuid,warehouse_id uuid
) ON COMMIT DROP;
CREATE TEMP TABLE erp_local_stock_operation (
 id uuid,tenant_id uuid,operation_type text,source_warehouse_id uuid,
 product_sku_id uuid,requested_quantity integer,applied_delta integer
) ON COMMIT DROP;
CREATE TEMP TABLE erp_local_lot_position (
 id uuid,tenant_id uuid,receipt_id uuid,product_sku_id uuid,warehouse_id uuid
) ON COMMIT DROP;
CREATE TEMP TABLE erp_receipt_quality_batch (
 tenant_id uuid,receipt_id uuid,lot_code text,status text,released_at timestamptz,
 product_sku_id uuid,inspection_id uuid
) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_receipt (
 id uuid,tenant_id uuid,quantity integer,purchase_order_line_id uuid,
 purchase_order_id uuid
) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_order_line (
 id uuid,tenant_id uuid,purchase_order_id uuid,product_sku_id uuid,
 unit_cost numeric(20,6)
) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_order (
 id uuid,tenant_id uuid,currency_code char(3)
) ON COMMIT DROP;
CREATE TEMP TABLE erp_purchase_receipt_qc (
 id uuid,tenant_id uuid,purchase_receipt_id uuid,
 accepted_quantity integer,rejected_quantity integer
) ON COMMIT DROP;
CREATE TEMP TABLE erp_local_stock_movement (
 tenant_id uuid,operation_id uuid,inspection_id uuid,
 movement_type text,quantity integer
) ON COMMIT DROP;
-- Copy real CHECKs, defaults, uniqueness and NOT NULL, but never FK to public
-- finance tables; all generated claims live only in this TEMP shadow.
CREATE TEMP TABLE finance_sale_lot_claim_v106
 (LIKE public.finance_sale_lot_claim_v106
  INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES) ON COMMIT DROP;
CREATE TEMP TABLE finance_sale_lot_retraction_v106 (
 tenant_id uuid,claim_id uuid
) ON COMMIT DROP;
CREATE TRIGGER v203_temp_capture BEFORE INSERT ON finance_sale_lot_claim_v106
 FOR EACH ROW EXECUTE FUNCTION public.finance_sale_lot_capture_v106();
CREATE TRIGGER v203_temp_immutable BEFORE UPDATE OR DELETE
 ON finance_sale_lot_claim_v106 FOR EACH ROW
 EXECUTE FUNCTION public.finance_sale_lot_immutable_v106();
CREATE TEMP VIEW v203_integrity AS
{view}
INSERT INTO sales_order VALUES ('{order}','{tenant}','{mp}','LIVE',
 '2026-10-01T10:00:00Z');
INSERT INTO order_item VALUES
 ('{item}','{tenant}','{order}',3,'AMZ-ITEM-1',true,
 '2026-10-01T10:00:00Z');
INSERT INTO erp_purchase_order VALUES ('{po}','{tenant}','USD');
INSERT INTO erp_purchase_order_line VALUES
 ('{pol}','{tenant}','{po}','{sku}',4.250000);
INSERT INTO erp_purchase_receipt VALUES
 ('{receipt}','{tenant}',4,'{pol}','{po}');
INSERT INTO erp_receipt_quality_batch VALUES
 ('{tenant}','{receipt}','LOT-A','RELEASED',
 '2026-09-30T10:00:00Z','{sku}','{inspection}');
INSERT INTO erp_purchase_receipt_qc VALUES
 ('{inspection}','{tenant}','{receipt}',4,0);
INSERT INTO erp_local_lot_position VALUES
 ('{pos}','{tenant}','{receipt}','{sku}','{warehouse}');
INSERT INTO erp_local_stock_operation VALUES
 ('{op}','{tenant}','OUTBOUND','{warehouse}','{sku}',2,-2);
INSERT INTO erp_local_lot_pick VALUES
 ('{pick}','{tenant}','{op}',2,'{sku}','{warehouse}');
INSERT INTO erp_local_lot_pick_line VALUES
 ('{line}','{tenant}','{pick}','{pos}',2);
INSERT INTO erp_local_stock_movement VALUES
 ('{tenant}',NULL,'{inspection}','QC_RELEASE',4),
 ('{tenant}','{op}',NULL,'OUTBOUND',-2);
INSERT INTO finance_sale_lot_claim_v106
 (id,tenant_id,marketplace_id,sales_order_id,order_item_id,pick_line_id,
  receipt_id,assigned_quantity,external_dispatch_ref,external_payload_sha256,
  snapshot_external_item_id,recorded_by)
 VALUES ('{claim}','{tenant}','{mp}','{order}','{item}','{line}',
 '{receipt}',2,'LOCAL-UNVERIFIED-001','{hashval}','FORGED',{repr(actor)}::uuid);
DO $$ BEGIN
 IF NOT EXISTS (
   SELECT 1 FROM v203_integrity
   WHERE claim_id='{claim}' AND integrity_state='LOCAL_LINK_INTACT_UNVERIFIED'
     AND snapshot_external_item_id='AMZ-ITEM-1'
     AND order_item_allocated=2 AND pick_line_allocated=2 AND receipt_allocated=2
     AND external_operator_verified=FALSE
     AND platform_fulfillment_verified=FALSE AND actual_batch_cogs_verified=FALSE
     AND realized_batch_cogs IS NULL
 ) THEN RAISE EXCEPTION 'V203: intact local claim must remain unverified'; END IF;
END $$;
-- Same pick/outbound segment cannot be attributed again under another claim id.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_sale_lot_claim_v106
   (id,tenant_id,marketplace_id,sales_order_id,order_item_id,pick_line_id,
    receipt_id,assigned_quantity,external_dispatch_ref,external_payload_sha256,
    recorded_by)
   VALUES ('{claim2}','{tenant}','{mp}','{order}','{item}','{line}',
    '{receipt}',1,'LOCAL-UNVERIFIED-002','{hashval}','{actor}');
  RAISE EXCEPTION 'V203: duplicate pick allocation accepted';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;
END $$;
INSERT INTO erp_local_stock_operation VALUES
 ('{op2}','{tenant}','OUTBOUND','{warehouse}','{sku}',2,-2);
INSERT INTO erp_local_lot_pick VALUES
 ('{pick2}','{tenant}','{op2}',2,'{sku}','{warehouse}');
INSERT INTO erp_local_lot_position VALUES
 ('{pos2}','{tenant}','{receipt}','{sku}','{warehouse}');
INSERT INTO erp_local_lot_pick_line VALUES
 ('{line2}','{tenant}','{pick2}','{pos2}',2);
INSERT INTO erp_local_stock_movement VALUES
 ('{tenant}','{op2}',NULL,'OUTBOUND',-2);
-- Item-level 2+2 > 3, despite using different pick lines.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_sale_lot_claim_v106
   (id,tenant_id,marketplace_id,sales_order_id,order_item_id,pick_line_id,
    receipt_id,assigned_quantity,external_dispatch_ref,external_payload_sha256,
    recorded_by)
   VALUES ('{claim2}','{tenant}','{mp}','{order}','{item}','{line2}',
    '{receipt}',2,'LOCAL-UNVERIFIED-002','{hashval}','{actor}');
  RAISE EXCEPTION 'V203: cross-pick item over-allocation accepted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%quantity conservation exceeded%' THEN RAISE; END IF;
 END;
END $$;
-- With a larger item, receipt accepted units still must not be over-used.
UPDATE order_item SET quantity=5 WHERE id='{item}';
UPDATE erp_purchase_receipt_qc SET accepted_quantity=3,rejected_quantity=1
 WHERE id='{inspection}';
UPDATE erp_local_stock_movement SET quantity=3
 WHERE inspection_id='{inspection}';
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_sale_lot_claim_v106
   (id,tenant_id,marketplace_id,sales_order_id,order_item_id,pick_line_id,
    receipt_id,assigned_quantity,external_dispatch_ref,external_payload_sha256,
    recorded_by)
   VALUES ('{claim2}','{tenant}','{mp}','{order}','{item}','{line2}',
    '{receipt}',2,'LOCAL-UNVERIFIED-002','{hashval}','{actor}');
  RAISE EXCEPTION 'V203: cross-event receipt over-allocation accepted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%quantity conservation exceeded%' THEN RAISE; END IF;
 END;
END $$;
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM v203_integrity
   WHERE claim_id='{claim}' AND integrity_state='SALE_EVIDENCE_STALE') THEN
  RAISE EXCEPTION 'V203: changed sale quantity not detected'; END IF;
END $$;
UPDATE order_item SET quantity=3 WHERE id='{item}';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM v203_integrity
   WHERE claim_id='{claim}' AND integrity_state='PURCHASE_BATCH_EVIDENCE_STALE')
 THEN RAISE EXCEPTION 'V203: QC acceptance drift not detected'; END IF;
END $$;
UPDATE erp_purchase_receipt_qc SET accepted_quantity=4,rejected_quantity=0
 WHERE id='{inspection}';
UPDATE erp_local_stock_movement SET quantity=4
 WHERE inspection_id='{inspection}';
UPDATE erp_local_lot_pick_line SET quantity=1 WHERE id='{line}';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM v203_integrity
   WHERE claim_id='{claim}' AND integrity_state='PICK_OUTBOUND_EVIDENCE_STALE')
 THEN RAISE EXCEPTION 'V203: pick quantity drift not detected'; END IF;
END $$;
UPDATE erp_local_lot_pick_line SET quantity=2 WHERE id='{line}';
INSERT INTO finance_sale_lot_retraction_v106 VALUES ('{tenant}','{claim}');
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM v203_integrity
   WHERE claim_id='{claim}' AND integrity_state='RETRACTED'
     AND receipt_allocated=2) THEN
  RAISE EXCEPTION 'V203: retracted claim lost its conservation reservation'; END IF;
END $$;
-- A retraction NEVER releases units for a speculative second attribution.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_sale_lot_claim_v106
   (id,tenant_id,marketplace_id,sales_order_id,order_item_id,pick_line_id,
    receipt_id,assigned_quantity,external_dispatch_ref,external_payload_sha256,
    recorded_by)
   VALUES ('{claim2}','{tenant}','{mp}','{order}','{item}','{line2}',
    '{receipt}',2,'LOCAL-UNVERIFIED-002','{hashval}','{actor}');
  RAISE EXCEPTION 'V203: retraction double-spend accepted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%quantity conservation exceeded%' THEN RAISE; END IF;
 END;
END $$;
DO $$ BEGIN
 BEGIN
  UPDATE finance_sale_lot_claim_v106 SET assigned_quantity=1
   WHERE id='{claim}';
  RAISE EXCEPTION 'V203: immutable claim accepted update';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%append-only%' THEN RAISE; END IF;
 END;
END $$;
ROLLBACK;
"""
# Tests run against the disposable PostgreSQL 18 service used by existing CI.
proc=subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
 "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
 input=sql,capture_output=True,text=True,timeout=120,check=False)
if proc.returncode:
    raise SystemExit("V203 PG18 source-exact negative test failed:\n"+proc.stderr[-4500:])
print("PASS V203 PG18 TEMP: real Flyway function and independent view; "
      "sale/pick/receipt conservation, unique pick claim, stale QC/sale/pick, "
      "append-only retraction; external proof and actual COGS remain blocked")
