#!/usr/bin/env python3
"""V208 PG18 original V111 guards/views with TEMP/ROLLBACK synthetic fixtures.

PostgreSQL captures exact V109 byte SHA, document kind, V108 order/dispatch
identity and source revocation; Java alone recalculates actual Ed25519.
Tenant-enrolled key possession IS NOT verified issuer identity or B2 trust.
"""
import base64
import hashlib
import os
import re
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

if os.environ.get("MERCHIVRA_V208_DISPOSABLE_ONLY")!="true":
    raise SystemExit("V208 real PG18 disposable-only opt-in required")
root=Path("apps/server/src/main/resources/db/migration")
v109=(root/"V109__custody_original_content_recomputation.sql").read_text()
v111=(root/"V111__custody_claimed_issuer_signature_evidence.sql").read_text()
def source_view(sql,name,stop):
    marker="CREATE VIEW "+name+" AS\n"
    if sql.count(marker)!=1 or sql.count(stop)!=1:
        raise SystemExit("V208 exact source view marker missing: "+name)
    body=sql.split(marker,1)[1].split(stop,1)[0]
    if not body.rstrip().endswith(";"):
        raise SystemExit("V208 truncated view "+name)
    return body
original_view=source_view(v109,"finance_dispatch_custody_original_integrity_v109",
    "\nCOMMENT ON VIEW finance_dispatch_custody_original_integrity_v109")
issuer_view=source_view(v111,"finance_custody_issuer_signature_integrity_v111",
    "\nCOMMENT ON VIEW finance_custody_issuer_signature_integrity_v111")
def uid(n):
    return "00000000-0000-4000-8000-"+f"{n:012x}"
ids={k:uid(0xE800+i) for i,k in enumerate([
 "tenant","foreign_tenant","marketplace","order","dossier","dispatch",
 "operator_file","carrier_file","operator_key","carrier_key",
 "signature","signature2","uploader","submitter","registrar",
 "reviewer","importer","revoker","other"])}
operator=b"%PDF-1.7\nV208_SYNTHETIC_OPERATOR_DOCUMENT"
carrier=b"\x89PNG\r\n\x1a\nV208_SYNTHETIC_CARRIER_DOCUMENT"
h1=hashlib.sha256(operator).hexdigest()
h2=hashlib.sha256(carrier).hexdigest()
# DER Ed25519 SPKI syntax only: synthetic SQL fixtures are NOT actual
# externally signed objects; real crypto positive/negative tests live in Java.
key1=base64.b64encode(bytes.fromhex("302a300506032b6570032100")+b"\x11"*32).decode()
key2=base64.b64encode(bytes.fromhex("302a300506032b6570032100")+b"\x22"*32).decode()
sig=base64.b64encode(b"\x33"*64).decode()
issued=(datetime.now(timezone.utc)-timedelta(minutes=5)).replace(microsecond=0)
date=issued.isoformat().replace("+00:00","Z")
sql="""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::int<180000 THEN
  RAISE EXCEPTION 'V208 requires real PostgreSQL 18';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history
   WHERE version='111' AND success) THEN
  RAISE EXCEPTION 'V111 Flyway must be applied first';
 END IF;
END $$;
CREATE TEMP TABLE finance_dispatch_custody_integrity_v108 (
 dossier_id UUID,tenant_id UUID,marketplace_id UUID,sales_order_id UUID,
 signed_dispatch_id UUID,integrity_state TEXT,
 operator_document_sha256 CHAR(64),carrier_document_sha256 CHAR(64)
) ON COMMIT DROP;
CREATE TEMP TABLE finance_dispatch_custody_dossier_v108 (
 id UUID,tenant_id UUID,marketplace_id UUID,sales_order_id UUID,
 signed_dispatch_id UUID,operator_registry_ref TEXT,carrier_handoff_ref TEXT,
 submitted_by UUID
) ON COMMIT DROP;
CREATE TEMP TABLE finance_dispatch_custody_original_v109
 (LIKE public.finance_dispatch_custody_original_v109 INCLUDING ALL) ON COMMIT DROP;
CREATE TEMP TABLE finance_custody_issuer_key_v111
 (LIKE public.finance_custody_issuer_key_v111 INCLUDING ALL) ON COMMIT DROP;
CREATE TEMP TABLE finance_custody_issuer_signature_v111
 (LIKE public.finance_custody_issuer_signature_v111 INCLUDING ALL) ON COMMIT DROP;
"""
sql+="CREATE TEMP VIEW finance_dispatch_custody_original_integrity_v109 AS\n"+original_view+"\n"
sql+="CREATE TEMP VIEW finance_custody_issuer_signature_integrity_v111 AS\n"+issuer_view+"\n"
sql+="""
CREATE TRIGGER v208_real_key_guard
 BEFORE UPDATE OR DELETE ON finance_custody_issuer_key_v111
 FOR EACH ROW EXECUTE FUNCTION public.finance_custody_issuer_key_guard_v111();
CREATE TRIGGER v208_real_signature_capture
 BEFORE INSERT ON finance_custody_issuer_signature_v111
 FOR EACH ROW EXECUTE FUNCTION public.finance_custody_issuer_signature_capture_v111();
CREATE TRIGGER v208_real_signature_immutable
 BEFORE UPDATE OR DELETE ON finance_custody_issuer_signature_v111
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_append_only_v98();
INSERT INTO finance_dispatch_custody_dossier_v108 VALUES
 ('@dossier@','@tenant@','@marketplace@','@order@','@dispatch@',
  'OPERATOR-REF-208','CARRIER-REF-208','@submitter@');
INSERT INTO finance_dispatch_custody_integrity_v108 VALUES
 ('@dossier@','@tenant@','@marketplace@','@order@','@dispatch@',
 'INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED','@hash1@','@hash2@');
INSERT INTO finance_dispatch_custody_original_v109
 (id,tenant_id,dossier_id,document_kind,media_type,file_bytes,uploaded_by)
VALUES
 ('@operator_file@','@tenant@','@dossier@','OPERATOR_REGISTRY',
  'application/pdf',decode('@operator_hex@','hex'),'@uploader@'),
 ('@carrier_file@','@tenant@','@dossier@','CARRIER_HANDOFF',
  'image/png',decode('@carrier_hex@','hex'),'@uploader@');
INSERT INTO finance_custody_issuer_key_v111
 (id,tenant_id,document_kind,claimed_issuer_ref,
  ed25519_spki_base64,registered_by)
VALUES
 ('@operator_key@','@tenant@','OPERATOR_REGISTRY',
  'CLAIMED-OPERATOR-ISSUER-208','@key1@','@registrar@'),
 ('@carrier_key@','@tenant@','CARRIER_HANDOFF',
  'CLAIMED-CARRIER-ISSUER-208','@key2@','@registrar@');

DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM finance_custody_issuer_key_v111
   WHERE external_issuer_identity_verified OR independent_trust_anchor_verified)
 THEN RAISE EXCEPTION 'V208 tenant key already externally trusted'; END IF;
 BEGIN
  UPDATE finance_custody_issuer_key_v111
    SET state='REVIEWED_UNVERIFIED',reviewed_by='@registrar@',
     reviewed_at=now(),review_reason='Key registrar cannot self-review'
    WHERE id='@operator_key@';
  RAISE EXCEPTION 'V208 source self-review accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
END $$;
UPDATE finance_custody_issuer_key_v111
 SET state='REVIEWED_UNVERIFIED',reviewed_by='@reviewer@',
  reviewed_at=now(),review_reason='Independent internal tenant key review only'
 WHERE id='@operator_key@';
UPDATE finance_custody_issuer_key_v111
 SET state='REVIEWED_UNVERIFIED',reviewed_by='@reviewer@',
  reviewed_at=now(),review_reason='Independent tenant carrier key review only'
 WHERE id='@carrier_key@';

-- Exact source trigger refuses self-import, wrong kind, wrong tenant,
-- missing V109 content or drifted parent evidence.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_custody_issuer_signature_v111
    (id,tenant_id,dossier_id,original_file_id,issuer_key_id,document_kind,
     claimed_issued_at,signature_base64,canonical_sha256,imported_by)
  VALUES ('@signature@','@tenant@','@dossier@','@operator_file@',
    '@operator_key@','OPERATOR_REGISTRY','@issued@','@signature_b64@',
    '@canonical_digest@','@registrar@');
  RAISE EXCEPTION 'V208 key registrar self-import accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
 BEGIN
  INSERT INTO finance_custody_issuer_signature_v111
    (id,tenant_id,dossier_id,original_file_id,issuer_key_id,document_kind,
     claimed_issued_at,signature_base64,canonical_sha256,imported_by)
  VALUES ('@signature@','@tenant@','@dossier@','@operator_file@',
    '@operator_key@','OPERATOR_REGISTRY','@issued@','@signature_b64@',
    '@canonical_digest@','@uploader@');
  RAISE EXCEPTION 'V208 original uploader self-import accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
 BEGIN
  INSERT INTO finance_custody_issuer_signature_v111
    (id,tenant_id,dossier_id,original_file_id,issuer_key_id,document_kind,
     claimed_issued_at,signature_base64,canonical_sha256,imported_by)
  VALUES ('@signature@','@tenant@','@dossier@','@operator_file@',
    '@operator_key@','OPERATOR_REGISTRY','@issued@','@signature_b64@',
    '@canonical_digest@','@submitter@');
  RAISE EXCEPTION 'V208 dossier submitter self-import accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
 BEGIN
  INSERT INTO finance_custody_issuer_signature_v111
    (id,tenant_id,dossier_id,original_file_id,issuer_key_id,document_kind,
     claimed_issued_at,signature_base64,canonical_sha256,imported_by)
  VALUES ('@signature@','@foreign_tenant@','@dossier@','@operator_file@',
    '@operator_key@','OPERATOR_REGISTRY','@issued@','@signature_b64@',
    '@canonical_digest@','@importer@');
  RAISE EXCEPTION 'V208 cross-tenant signature accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
 BEGIN
  INSERT INTO finance_custody_issuer_signature_v111
    (id,tenant_id,dossier_id,original_file_id,issuer_key_id,document_kind,
     claimed_issued_at,signature_base64,canonical_sha256,imported_by)
  VALUES ('@signature@','@tenant@','@dossier@','@operator_file@',
    '@carrier_key@','OPERATOR_REGISTRY','@issued@','@signature_b64@',
    '@canonical_digest@','@importer@');
  RAISE EXCEPTION 'V208 wrong kind issuer key accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
END $$;
-- Deliberately supply fabricated snapshots: PG must overwrite all of them
-- with original source facts (the signature is still Java-cryptographically
-- UNCHECKED in SQL and cannot claim external issuer identity).
INSERT INTO finance_custody_issuer_signature_v111 (
 id,tenant_id,dossier_id,original_file_id,issuer_key_id,document_kind,
 snapshot_marketplace_id,snapshot_sales_order_id,snapshot_signed_dispatch_id,
 snapshot_document_ref,snapshot_file_sha256,snapshot_issuer_ref,
 claimed_issued_at,signature_base64,canonical_sha256,imported_by)
VALUES ('@signature@','@tenant@','@dossier@','@operator_file@','@operator_key@',
 'OPERATOR_REGISTRY','@other@','@other@','@other@','FAKE-REF-208',
 '@hash2@','FAKE-ISSUER-208','@issued@','@signature_b64@',
 '@canonical_digest@','@importer@');
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM finance_custody_issuer_signature_integrity_v111
  WHERE id='@signature@' AND snapshot_marketplace_id='@marketplace@'
   AND snapshot_sales_order_id='@order@'
   AND snapshot_signed_dispatch_id='@dispatch@'
   AND snapshot_document_ref='OPERATOR-REF-208'
   AND snapshot_file_sha256='@hash1@'
   AND snapshot_issuer_ref='CLAIMED-OPERATOR-ISSUER-208'
   AND evidence_state='TENANT_KEY_SOURCE_CURRENT_CRYPTO_RECOMPUTE_REQUIRED'
   AND NOT external_issuer_identity_verified
   AND NOT independent_trust_anchor_verified
   AND NOT physical_handoff_verified
   AND NOT amazon_fulfillment_verified
   AND NOT actual_batch_cogs_verified
   AND realized_batch_cogs IS NULL) THEN
  RAISE EXCEPTION 'V208 source-exact snapshot capture or untrusted scope broken';
 END IF;
END $$;
INSERT INTO finance_custody_issuer_signature_v111
 (id,tenant_id,dossier_id,original_file_id,issuer_key_id,document_kind,
 claimed_issued_at,signature_base64,canonical_sha256,imported_by)
VALUES ('@signature2@','@tenant@','@dossier@','@carrier_file@',
 '@carrier_key@','CARRIER_HANDOFF','@issued@','@signature_b64@',
 '@canonical_digest@','@importer@');

-- No replay of a claimed original, even if it signs the same hash/key again.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_custody_issuer_signature_v111
   (id,tenant_id,dossier_id,original_file_id,issuer_key_id,document_kind,
    claimed_issued_at,signature_base64,canonical_sha256,imported_by)
  VALUES('@other@','@tenant@','@dossier@','@operator_file@','@operator_key@',
    'OPERATOR_REGISTRY','@issued@','@signature_b64@',
    '@canonical_digest@','@importer@');
  RAISE EXCEPTION 'V208 duplicate issuer signature inserted';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
  UPDATE finance_custody_issuer_key_v111
   SET claimed_issuer_ref='FORGED-ISSUER-208' WHERE id='@operator_key@';
  RAISE EXCEPTION 'V208 source issuer reference rewritable';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
 BEGIN
  UPDATE finance_custody_issuer_signature_v111
   SET snapshot_document_ref='FORGED-REF-208' WHERE id='@signature@';
  RAISE EXCEPTION 'V208 captured signature mutated';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%cannot be changed or deleted%' THEN RAISE; END IF;
 END;
END $$;

-- Source revocation propagates immediately to older signed observations.
UPDATE finance_custody_issuer_key_v111
 SET state='REVOKED',revoked_by='@revoker@',revoked_at=now(),
  revoke_reason='External trust cannot be established for this issuer'
 WHERE id='@operator_key@';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_custody_issuer_signature_integrity_v111
  WHERE id='@signature@'
    AND evidence_state='ISSUER_KEY_REVOKED_OR_UNREVIEWED') THEN
  RAISE EXCEPTION 'V208 revoked issuer key did not invalidate history';
 END IF;
 BEGIN
  UPDATE finance_custody_issuer_key_v111
   SET state='REVIEWED_UNVERIFIED' WHERE id='@operator_key@';
  RAISE EXCEPTION 'V208 revoked claimed key reactivated';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
END $$;
UPDATE finance_dispatch_custody_integrity_v108
 SET integrity_state='SIGNED_DISPATCH_STALE' WHERE dossier_id='@dossier@';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_custody_issuer_signature_integrity_v111
  WHERE id='@signature2@' AND evidence_state='ORIGINALS_OR_UPSTREAM_STALE') THEN
  RAISE EXCEPTION 'V208 V204 upstream invalidation not propagated';
 END IF;
END $$;
ROLLBACK;
"""
for k,v in ids.items():
    sql=sql.replace("@"+k+"@",v)
for k,v in {
 "hash1":h1,"hash2":h2,"operator_hex":operator.hex(),
 "carrier_hex":carrier.hex(),"key1":key1,"key2":key2,
 "signature_b64":sig,"issued":date,"canonical_digest":"a"*64
}.items():
    sql=sql.replace("@"+k+"@",v)
if re.search(r"@[a-z0-9_]+@",sql):
    raise SystemExit("V208 missing synthetic field substitution")
proc=subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
 "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
 input=sql,text=True,capture_output=True,timeout=120,check=False)
if proc.returncode:
    raise SystemExit("V208 PG18 source-exact SQL failure:\n"+proc.stderr[-5500:])
print("PASS V208 PG18 V111 exact source capture/key guards/view: separate "
      "operator/carrier sources, self-review/import and tenant rejection, "
      "source-owned identity snapshots, duplicate and immutable signatures, "
      "revoked key/upstream invalidation, hard false external issuer, "
      "fulfillment and COGS; TEMP/ROLLBACK only, Java verifies real Ed25519")
