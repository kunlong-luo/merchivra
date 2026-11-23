#!/usr/bin/env python3
"""V206 PG18 source-exact migration SQL on disposable TEMP tables.

Uses installed public V109 trigger, extracted original read-time view SQL,
and synthetic short PDF/PNG originals. Never obtains real external issuer,
carrier, Amazon, bank or physical stock records. ROLLBACK after assertions.
"""
import hashlib
import os
import re
import subprocess
from pathlib import Path

if os.environ.get("MERCHIVRA_V206_DISPOSABLE_ONLY")!="true":
    raise SystemExit("V206 requires explicit disposable PG18 opt-in")
migration=Path("apps/server/src/main/resources/db/migration/"
               "V109__custody_original_content_recomputation.sql").read_text()
start="CREATE VIEW finance_dispatch_custody_original_integrity_v109 AS\n"
stop="\nCOMMENT ON VIEW finance_dispatch_custody_original_integrity_v109"
if migration.count(start)!=1 or migration.count(stop)!=1:
    raise SystemExit("V206 source-exact view markers missing or duplicate")
view=migration.split(start,1)[1].split(stop,1)[0]
if not view.rstrip().endswith(";"):
    raise SystemExit("V206 extracted view missing semicolon")
def uid(n):
    return "00000000-0000-4000-8000-"+f"{n:012x}"
ids={k:uid(0xE600+i) for i,k in enumerate([
    "tenant","other_tenant","mp","order","dossier","dossier2",
    "dispatch","file1","file2","file3","actor","other"
])}
op=b"%PDF-1.7\nSYNTHETIC_OPERATOR_DOCUMENT_V206"
carrier=b"\x89PNG\r\n\x1a\nSYNTHETIC_CARRIER_DOCUMENT_V206"
bad=b"%PDF-1.7\nALTERED_FOR_TAMPER_NEGATIVE_CASE"
hash_op=hashlib.sha256(op).hexdigest()
hash_car=hashlib.sha256(carrier).hexdigest()
sql="""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::int<180000 THEN
  RAISE EXCEPTION 'V206 PG18 required';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history
   WHERE version='109' AND success) THEN
  RAISE EXCEPTION 'Flyway V109 missing; run actual migration first';
 END IF;
END $$;
CREATE TEMP TABLE tenant (id UUID,status TEXT) ON COMMIT DROP;
INSERT INTO tenant VALUES('@tenant@','ACTIVE'),('@other_tenant@','ACTIVE');
CREATE TEMP TABLE finance_dispatch_custody_dossier_v108 (
 id UUID,tenant_id UUID,operator_document_sha256 CHAR(64),
 carrier_document_sha256 CHAR(64)
) ON COMMIT DROP;
CREATE TEMP TABLE finance_dispatch_custody_integrity_v108 (
 dossier_id UUID,tenant_id UUID,marketplace_id UUID,sales_order_id UUID,
 signed_dispatch_id UUID,integrity_state TEXT,
 operator_document_sha256 CHAR(64),carrier_document_sha256 CHAR(64)
) ON COMMIT DROP;
CREATE TEMP TABLE finance_dispatch_custody_original_v109 (
 LIKE public.finance_dispatch_custody_original_v109 INCLUDING ALL
) ON COMMIT DROP;
CREATE TRIGGER v206_real_original_guard
 BEFORE INSERT ON finance_dispatch_custody_original_v109
 FOR EACH ROW EXECUTE FUNCTION public.finance_dispatch_custody_original_guard_v109();
CREATE TRIGGER v206_real_immutable_guard
 BEFORE UPDATE OR DELETE ON finance_dispatch_custody_original_v109
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_append_only_v98();
"""
sql+="CREATE TEMP VIEW v206_integrity AS\n"+view+"\n"
sql+="""
INSERT INTO finance_dispatch_custody_dossier_v108 VALUES
 ('@dossier@','@tenant@','@hash_op@','@hash_car@'),
 ('@dossier2@','@tenant@','@hash_op@','@hash_car@');
INSERT INTO finance_dispatch_custody_integrity_v108 VALUES
 ('@dossier@','@tenant@','@mp@','@order@','@dispatch@',
  'INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED','@hash_op@','@hash_car@'),
 ('@dossier2@','@tenant@','@mp@','@order@','@dispatch@',
  'EXTERNAL_REFERENCE_DIGEST_CONFLICT','@hash_op@','@hash_car@');

-- No originals must not appear ready for external identity or actual COGS.
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM v206_integrity WHERE dossier_id='@dossier@'
  AND original_state='ORIGINAL_CONTENT_MISSING'
  AND operator_file_id IS NULL AND carrier_file_id IS NULL
  AND NOT operator_identity_verified AND NOT physical_handoff_verified
  AND NOT platform_fulfillment_verified AND NOT actual_batch_cogs_verified
  AND realized_batch_cogs IS NULL) THEN
  RAISE EXCEPTION 'V206 absent originals falsely passed';
 END IF;
END $$;

-- The first private file is a valid synthetic PDF whose hash the database
-- computes, rather than accepting a fake hash from the INSERT caller.
INSERT INTO finance_dispatch_custody_original_v109
 (id,tenant_id,dossier_id,document_kind,media_type,file_bytes,uploaded_by)
VALUES('@file1@','@tenant@','@dossier@','OPERATOR_REGISTRY',
 'application/pdf',decode('@op_hex@','hex'),'@actor@');
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_dispatch_custody_original_v109
  WHERE id='@file1@' AND size_bytes=@op_len@
   AND sha256='@hash_op@'
   AND NOT operator_identity_verified AND NOT cogs_posted) THEN
  RAISE EXCEPTION 'V206 generated source-bytes digest/size invalid';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM v206_integrity WHERE dossier_id='@dossier@'
  AND original_state='ORIGINAL_CONTENT_MISSING') THEN
  RAISE EXCEPTION 'V206 one of two documents accepted as complete';
 END IF;
END $$;

-- PDF magic mismatched to claimed MIME is rejected at the database layer.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_dispatch_custody_original_v109
   (id,tenant_id,dossier_id,document_kind,media_type,file_bytes,uploaded_by)
  VALUES('@file2@','@tenant@','@dossier@','OPERATOR_REGISTRY',
   'image/png',decode('@op_hex@','hex'),'@actor@');
  RAISE EXCEPTION 'V206 wrong file MIME magic bypassed PostgreSQL';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
END $$;
-- Two references cannot be filled by a different binary just because
-- its name/kind or submitter claims it is a carrier document.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_dispatch_custody_original_v109
   (id,tenant_id,dossier_id,document_kind,media_type,file_bytes,uploaded_by)
  VALUES('@file2@','@tenant@','@dossier@','CARRIER_HANDOFF',
   'application/pdf',decode('@bad_hex@','hex'),'@actor@');
  RAISE EXCEPTION 'V206 accepted mismatched carrier SHA';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
END $$;

INSERT INTO finance_dispatch_custody_original_v109
 (id,tenant_id,dossier_id,document_kind,media_type,file_bytes,uploaded_by)
VALUES('@file2@','@tenant@','@dossier@','CARRIER_HANDOFF',
 'image/png',decode('@car_hex@','hex'),'@actor@');
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM v206_integrity WHERE dossier_id='@dossier@'
  AND original_state='ORIGINALS_MATCH_CLAIMS_EXTERNAL_UNVERIFIED'
  AND operator_saved_sha256='@hash_op@'
  AND carrier_saved_sha256='@hash_car@'
  AND NOT operator_identity_verified AND NOT physical_handoff_verified
  AND NOT platform_fulfillment_verified AND NOT actual_batch_cogs_verified
  AND realized_batch_cogs IS NULL) THEN
  RAISE EXCEPTION 'V206 two correct originals improperly classified';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM v206_integrity WHERE dossier_id='@dossier2@'
  AND original_state='DOSSIER_OR_SIGNED_SOURCE_STALE') THEN
  RAISE EXCEPTION 'V206 disputed upstream dossier not held';
 END IF;
END $$;

-- An old valid byte/digest pairing must be re-evaluated if V205 is revoked.
UPDATE finance_dispatch_custody_integrity_v108
 SET integrity_state='SIGNED_DISPATCH_STALE'
 WHERE dossier_id='@dossier@';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM v206_integrity
  WHERE dossier_id='@dossier@' AND original_state='DOSSIER_OR_SIGNED_SOURCE_STALE')
  THEN RAISE EXCEPTION 'V206 old originals survived revoked upstream claim'; END IF;
END $$;
UPDATE finance_dispatch_custody_integrity_v108
 SET integrity_state='INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED'
 WHERE dossier_id='@dossier@';
UPDATE finance_dispatch_custody_integrity_v108
 SET operator_document_sha256='@hash_car@'
 WHERE dossier_id='@dossier@';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM v206_integrity
   WHERE dossier_id='@dossier@'
     AND original_state='ORIGINAL_CONTENT_DIGEST_STALE') THEN
  RAISE EXCEPTION 'V206 changed original document declaration undetected';
 END IF;
END $$;
UPDATE finance_dispatch_custody_integrity_v108
 SET operator_document_sha256='@hash_op@'
 WHERE dossier_id='@dossier@';

-- Multiple uploads for same kind are prohibited, even if the bytes match.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_dispatch_custody_original_v109
   (id,tenant_id,dossier_id,document_kind,media_type,file_bytes,uploaded_by)
  VALUES('@file3@','@tenant@','@dossier@','OPERATOR_REGISTRY',
   'application/pdf',decode('@op_hex@','hex'),'@actor@');
  RAISE EXCEPTION 'V206 duplicate file for same dossier/kind';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;
 BEGIN
  INSERT INTO finance_dispatch_custody_original_v109
   (id,tenant_id,dossier_id,document_kind,media_type,file_bytes,uploaded_by)
  VALUES('@file3@','@other_tenant@','@dossier@','OPERATOR_REGISTRY',
   'application/pdf',decode('@op_hex@','hex'),'@actor@');
  RAISE EXCEPTION 'V206 cross-tenant original accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
 BEGIN
  INSERT INTO finance_dispatch_custody_original_v109
   (id,tenant_id,dossier_id,document_kind,media_type,file_bytes,uploaded_by,
    operator_identity_verified)
  VALUES('@file3@','@tenant@','@dossier@','OPERATOR_REGISTRY',
   'application/pdf',decode('@op_hex@','hex'),'@actor@',TRUE);
  RAISE EXCEPTION 'V206 forged external verification TRUE accepted';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
END $$;

-- DB cannot mutate or erase previously verified uploaded bytes.
DO $$ BEGIN
 BEGIN
  UPDATE finance_dispatch_custody_original_v109 SET media_type='image/png'
   WHERE id='@file1@';
  RAISE EXCEPTION 'V206 private original mutated';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%cannot be changed or deleted%' THEN RAISE; END IF;
 END;
 BEGIN
  DELETE FROM finance_dispatch_custody_original_v109 WHERE id='@file2@';
  RAISE EXCEPTION 'V206 private original deleted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%cannot be changed or deleted%' THEN RAISE; END IF;
 END;
END $$;
ROLLBACK;
"""
for key,value in ids.items():
    sql=sql.replace("@"+key+"@",value)
for key,value in {
    "op_hex":op.hex(),"car_hex":carrier.hex(),"bad_hex":bad.hex(),
    "hash_op":hash_op,"hash_car":hash_car,"op_len":str(len(op))
}.items():
    sql=sql.replace("@"+key+"@",value)
if re.search(r"@[a-z0-9_]+@",sql):
    raise SystemExit("V206 missing synthetic fixture field substitution")
proc=subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
                      "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
                    input=sql,text=True,capture_output=True,
                    timeout=120,check=False)
if proc.returncode:
    raise SystemExit("V206 PG18 exact source failure:\n"+proc.stderr[-4500:])
print("PASS V206 PG18 V109 exact original trigger and source view: content SHA-256 "
      "generated/read-time recomputation, file magic, missing carrier, duplicate "
      "kind, cross-tenant, immutable evidence, upstream stale, and external "
      "identity / physical / platform / COGS hard false; synthetic TEMP/ROLLBACK")
