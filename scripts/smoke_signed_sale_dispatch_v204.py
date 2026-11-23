#!/usr/bin/env python3
"""V204 source-exact V107 SQL on disposable PostgreSQL 18 TEMP tables.

The Java tests independently exercise genuine Ed25519 verification. SQL-only
fixtures intentionally cannot create trusted signatures or prove WMS identity.
No real Amazon/Ads authorization, inventory changes or financial posting.
"""
import os
import re
from pathlib import Path
import subprocess

if os.environ.get("MERCHIVRA_V204_DISPOSABLE_ONLY") != "true":
    raise SystemExit("V204 requires explicit disposable PG18 opt-in")
migration=Path("apps/server/src/main/resources/db/migration/"
               "V107__signed_sale_dispatch_observation.sql").read_text()
needle="CREATE VIEW finance_sale_lot_dispatch_integrity_v107 AS\n"
if migration.count(needle)!=1:
    raise SystemExit("V204 missing source-exact V107 reconciliation view")
view=migration.split(needle,1)[1].split("\nCOMMENT ON VIEW",1)[0]
if not view.rstrip().endswith(";"):
    raise SystemExit("V204 view source appears truncated")
def uid(n):
    return "00000000-0000-4000-8000-"+f"{n:012x}"
ids={key:uid(i) for i,key in enumerate([
    "tenant","mp","order","claim","source","wh","item","pick","receipt",
    "actor","other","otherclaim","othersource","otherwh"
],start=0xD401)}
s="""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::int<180000 THEN
  RAISE EXCEPTION 'V204 PG18 required';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history
               WHERE version='107' AND success) THEN
  RAISE EXCEPTION 'V107 Flyway must be installed';
 END IF;
END $$;
CREATE TEMP TABLE finance_sale_lot_claim_v106 (
 id UUID,tenant_id UUID,marketplace_id UUID,sales_order_id UUID,
 order_item_id UUID,pick_line_id UUID,receipt_id UUID,
 assigned_quantity INTEGER,external_dispatch_ref TEXT,
 external_payload_sha256 CHAR(64),snapshot_external_item_id TEXT,
 snapshot_warehouse_id UUID
) ON COMMIT DROP;
CREATE TEMP TABLE finance_sale_lot_integrity_v106 (
 tenant_id UUID,claim_id UUID,integrity_state TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_wms_signing_source_v98 (
 id UUID,tenant_id UUID,warehouse_id UUID,state TEXT,
 ed25519_spki_base64 TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_wms_key_proof_v101 (
 tenant_id UUID,source_id UUID,warehouse_id UUID,result TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_sale_lot_signed_dispatch_v107
 (LIKE public.finance_sale_lot_signed_dispatch_v107
  INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES) ON COMMIT DROP;
CREATE TRIGGER v204_temp_db_guard
 BEFORE INSERT ON finance_sale_lot_signed_dispatch_v107
 FOR EACH ROW EXECUTE FUNCTION public.finance_sale_lot_signed_dispatch_guard_v107();
CREATE TRIGGER v204_temp_append_only
 BEFORE UPDATE OR DELETE ON finance_sale_lot_signed_dispatch_v107
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_append_only_v98();
"""
s+="CREATE TEMP VIEW v204_integrity AS\n"+view+"\n"
s+="""
INSERT INTO finance_sale_lot_claim_v106 VALUES (
 '@claim@','@tenant@','@mp@','@order@','@item@','@pick@',
 '@receipt@',2,'DISPATCH-204-A',
 '@hash@','AMZITEM204','@wh@');
INSERT INTO finance_sale_lot_integrity_v106 VALUES (
 '@tenant@','@claim@','LOCAL_LINK_INTACT_UNVERIFIED');
INSERT INTO finance_wms_signing_source_v98 VALUES (
 '@source@','@tenant@','@wh@','APPROVED','LOCAL-SYNTHETIC-PUBLIC-KEY');
-- Missing key proof: even an apparent matching claim must fail.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_sale_lot_signed_dispatch_v107 (
   id,tenant_id,marketplace_id,sales_order_id,claim_id,source_id,
   warehouse_id,external_dispatch_ref,quantity,observed_at,
   signature_base64,canonical_sha256,recorded_by)
  VALUES ('@otherclaim@','@tenant@','@mp@','@order@','@claim@',
   '@source@','@wh@','DISPATCH-204-A',2,now(),
   '@sig@','@hash@','@actor@');
  RAISE EXCEPTION 'V204 wrongly accepted source without V101 proof';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
END $$;
INSERT INTO finance_wms_key_proof_v101 VALUES (
 '@tenant@','@source@','@wh@','KEY_POSSESSION_ONLY');
INSERT INTO finance_sale_lot_signed_dispatch_v107 (
 id,tenant_id,marketplace_id,sales_order_id,claim_id,source_id,
 warehouse_id,external_dispatch_ref,quantity,observed_at,
 signature_base64,canonical_sha256,recorded_by)
VALUES ('@otherclaim@','@tenant@','@mp@','@order@','@claim@',
 '@source@','@wh@','DISPATCH-204-A',2,now(),
 '@sig@','@hash@','@actor@');
DO $$ BEGIN
 IF NOT EXISTS (
  SELECT 1 FROM v204_integrity
  WHERE claim_id='@claim@'
    AND evidence_state='TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED'
    AND NOT operator_identity_verified
    AND NOT physical_dispatch_verified
    AND NOT platform_fulfillment_verified
    AND NOT actual_batch_cogs_verified
    AND realized_batch_cogs IS NULL
 ) THEN RAISE EXCEPTION 'V204 unverified local evidence illegally promoted'; END IF;
END $$;
-- A signature cannot be reused as a second item or second observation.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_sale_lot_signed_dispatch_v107 (
   id,tenant_id,marketplace_id,sales_order_id,claim_id,source_id,
   warehouse_id,external_dispatch_ref,quantity,observed_at,
   signature_base64,canonical_sha256,recorded_by)
  VALUES ('@other@','@tenant@','@mp@','@order@','@claim@',
   '@source@','@wh@','DISPATCH-204-A',2,now(),
   '@sig@','@hash@','@actor@');
  RAISE EXCEPTION 'V204 duplicate claim or signature accepted';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;
END $$;
-- Source revocation invalidates even an already recorded observation.
UPDATE finance_wms_signing_source_v98 SET state='REVOKED'
 WHERE tenant_id='@tenant@' AND id='@source@';
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM v204_integrity
  WHERE evidence_state='SIGNING_SOURCE_REVOKED') THEN
  RAISE EXCEPTION 'V204 revoked key not invalidated'; END IF;
END $$;
UPDATE finance_wms_signing_source_v98 SET state='APPROVED'
 WHERE tenant_id='@tenant@' AND id='@source@';
UPDATE finance_sale_lot_integrity_v106 SET integrity_state='RETRACTED'
 WHERE tenant_id='@tenant@';
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM v204_integrity
  WHERE evidence_state='LOCAL_CLAIM_STALE') THEN
  RAISE EXCEPTION 'V204 retracted underlying claim not invalidated'; END IF;
END $$;
UPDATE finance_sale_lot_integrity_v106
 SET integrity_state='LOCAL_LINK_INTACT_UNVERIFIED' WHERE tenant_id='@tenant@';
DELETE FROM finance_wms_key_proof_v101 WHERE tenant_id='@tenant@';
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM v204_integrity
  WHERE evidence_state='KEY_POSSESSION_EVIDENCE_MISSING') THEN
  RAISE EXCEPTION 'V204 missing key possession not invalidated'; END IF;
END $$;
INSERT INTO finance_wms_key_proof_v101 VALUES (
 '@tenant@','@source@','@wh@','KEY_POSSESSION_ONLY');
UPDATE finance_sale_lot_claim_v106
 SET assigned_quantity=3 WHERE id='@claim@';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM v204_integrity
  WHERE evidence_state='SIGNED_DISPATCH_IDENTITY_CONFLICT') THEN
  RAISE EXCEPTION 'V204 altered quantity undetected'; END IF;
END $$;
UPDATE finance_sale_lot_claim_v106 SET assigned_quantity=2 WHERE id='@claim@';
-- Database does not permit a forged TRUE flag, even from a privileged client.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_sale_lot_signed_dispatch_v107 (
   id,tenant_id,marketplace_id,sales_order_id,claim_id,source_id,
   warehouse_id,external_dispatch_ref,quantity,observed_at,
   signature_base64,canonical_sha256,recorded_by,
   platform_fulfillment_verified)
  VALUES ('@other@','@tenant@','@mp@','@order@','@claim@',
   '@source@','@wh@','DISPATCH-204-A',2,now(),
   '@sig2@','@hash@','@actor@',TRUE);
  RAISE EXCEPTION 'V204 forged platform verification accepted';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
END $$;
DO $$ BEGIN
 BEGIN
  UPDATE finance_sale_lot_signed_dispatch_v107 SET quantity=1
  WHERE id='@otherclaim@';
  RAISE EXCEPTION 'V204 signed evidence mutated';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%cannot be changed or deleted%'
   THEN RAISE; END IF;
 END;
END $$;
-- Another tenant cannot borrow an existing signed source or claim.
INSERT INTO finance_sale_lot_claim_v106 VALUES (
 '@other@','@other@','@mp@','@order@','@item@','@pick@',
 '@receipt@',2,'DISPATCH-204-A',
 '@hash@','AMZITEM204','@wh@');
INSERT INTO finance_sale_lot_integrity_v106 VALUES (
 '@other@','@other@','LOCAL_LINK_INTACT_UNVERIFIED');
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_sale_lot_signed_dispatch_v107 (
   id,tenant_id,marketplace_id,sales_order_id,claim_id,source_id,
   warehouse_id,external_dispatch_ref,quantity,observed_at,
   signature_base64,canonical_sha256,recorded_by)
  VALUES ('@other@','@other@','@mp@','@order@','@other@',
   '@source@','@wh@','DISPATCH-204-A',2,now(),
   '@sig2@','@hash@','@actor@');
  RAISE EXCEPTION 'V204 cross-tenant source accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
END $$;
ROLLBACK;
"""
for k,v in ids.items():
    s=s.replace("@"+k+"@",v)
s=s.replace("@hash@","a"*64).replace("@sig@","A"*86+"==").replace("@sig2@","B"*86+"==")
if re.search(r"@[a-z]+@",s):
    raise SystemExit("V204 missing fixture id replacement")
proc=subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
                      "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
                    input=s,text=True,capture_output=True,check=False,timeout=120)
if proc.returncode:
    raise SystemExit("V204 PG18 source-exact failure:\n"+proc.stderr[-4000:])
print("PASS V204 PG18 V107 exact trigger/view: key possession, unique claim, "
      "source revocation, claim stale, quantity drift, tenant isolation, "
      "append-only and actual COGS hard false; TEMP/ROLLBACK synthetic only")
