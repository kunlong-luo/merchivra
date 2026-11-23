#!/usr/bin/env python3
"""V207 source-exact PG18 V110 governance test, TEMP/ROLLBACK, synthetic only."""
import hashlib
import os
import re
import subprocess
from pathlib import Path

if os.environ.get("MERCHIVRA_V207_DISPOSABLE_ONLY")!="true":
    raise SystemExit("Explicit V207 disposable PostgreSQL 18 opt-in required")
root=Path("apps/server/src/main/resources/db/migration")
v109=(root/"V109__custody_original_content_recomputation.sql").read_text()
v110=(root/"V110__custody_original_governance_audit.sql").read_text()
def exact_view(sql,name):
    marker="CREATE VIEW "+name+" AS\n"
    stop="\nCOMMENT ON VIEW "+name
    if sql.count(marker)!=1 or sql.count(stop)!=1:
        raise SystemExit("Source view missing: "+name)
    body=sql.split(marker,1)[1].split(stop,1)[0]
    if not body.rstrip().endswith(";"):
        raise SystemExit("Truncated "+name)
    return body
def uid(n):
    return "00000000-0000-4000-8000-"+f"{n:012x}"
ids={k:uid(0xE700+i) for i,k in enumerate([
 "tenant","other_tenant","mp","order","dossier","dispatch","file",
 "carrier","proposal","otherproposal","review","otherreview",
 "uploader","proposer","reviewer","other"])}
pdf=b"%PDF-1.7\nV207_SYNTHETIC_OPERATOR_ORIGINAL"
png=b"\x89PNG\r\n\x1a\nV207_SYNTHETIC_CARRIER_ORIGINAL"
hpdf=hashlib.sha256(pdf).hexdigest()
hpng=hashlib.sha256(png).hexdigest()
sql="""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::int<180000 THEN
   RAISE EXCEPTION 'V207 requires PostgreSQL 18';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history
   WHERE version='110' AND success) THEN
   RAISE EXCEPTION 'V110 Flyway missing';
 END IF;
END $$;
CREATE TEMP TABLE finance_dispatch_custody_integrity_v108 (
 dossier_id UUID,tenant_id UUID,marketplace_id UUID,sales_order_id UUID,
 signed_dispatch_id UUID,integrity_state TEXT,
 operator_document_sha256 CHAR(64),carrier_document_sha256 CHAR(64)
) ON COMMIT DROP;
CREATE TEMP TABLE finance_dispatch_custody_original_v109 (
 LIKE public.finance_dispatch_custody_original_v109 INCLUDING ALL
) ON COMMIT DROP;
CREATE TEMP TABLE finance_custody_original_retention_proposal_v110 (
 LIKE public.finance_custody_original_retention_proposal_v110 INCLUDING ALL
) ON COMMIT DROP;
CREATE TEMP TABLE finance_custody_original_retention_review_v110 (
 LIKE public.finance_custody_original_retention_review_v110 INCLUDING ALL
) ON COMMIT DROP;
CREATE TEMP TABLE finance_custody_original_metadata_access_v110 (
 LIKE public.finance_custody_original_metadata_access_v110 INCLUDING ALL
) ON COMMIT DROP;
"""
sql+="CREATE TEMP VIEW finance_dispatch_custody_original_integrity_v109 AS\n"+exact_view(v109,"finance_dispatch_custody_original_integrity_v109")+"\n"
sql+="CREATE TEMP VIEW finance_custody_original_governance_v110 AS\n"+exact_view(v110,"finance_custody_original_governance_v110")+"\n"
sql+="""
CREATE TRIGGER real_proposal BEFORE INSERT
 ON finance_custody_original_retention_proposal_v110
 FOR EACH ROW EXECUTE FUNCTION public.finance_custody_retention_proposal_guard_v110();
CREATE TRIGGER real_review BEFORE INSERT
 ON finance_custody_original_retention_review_v110
 FOR EACH ROW EXECUTE FUNCTION public.finance_custody_retention_review_guard_v110();
CREATE TRIGGER real_access BEFORE INSERT
 ON finance_custody_original_metadata_access_v110
 FOR EACH ROW EXECUTE FUNCTION public.finance_custody_metadata_access_guard_v110();
CREATE TRIGGER immutable_proposal BEFORE UPDATE OR DELETE
 ON finance_custody_original_retention_proposal_v110
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_append_only_v98();
CREATE TRIGGER immutable_review BEFORE UPDATE OR DELETE
 ON finance_custody_original_retention_review_v110
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_append_only_v98();
CREATE TRIGGER immutable_access BEFORE UPDATE OR DELETE
 ON finance_custody_original_metadata_access_v110
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_append_only_v98();
INSERT INTO finance_dispatch_custody_integrity_v108 VALUES
 ('@dossier@','@tenant@','@mp@','@order@','@dispatch@',
 'INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED','@pdf_hash@','@png_hash@');
INSERT INTO finance_dispatch_custody_original_v109
 (id,tenant_id,dossier_id,document_kind,media_type,file_bytes,uploaded_by)
VALUES
 ('@file@','@tenant@','@dossier@','OPERATOR_REGISTRY','application/pdf',
  decode('@pdf_hex@','hex'),'@uploader@'),
 ('@carrier@','@tenant@','@dossier@','CARRIER_HANDOFF','image/png',
  decode('@png_hex@','hex'),'@uploader@');
DO $$ BEGIN
 IF (SELECT count(*) FROM finance_custody_original_governance_v110
   WHERE legal_hold AND NOT deletion_permitted
    AND NOT operator_identity_verified AND NOT physical_handoff_verified
    AND NOT platform_fulfillment_verified AND NOT actual_batch_cogs_verified
    AND realized_batch_cogs IS NULL
    AND governance_state='DEFAULT_LEGAL_HOLD_NO_RETENTION')<>2 THEN
  RAISE EXCEPTION 'V207 default legal hold gate bypass';
 END IF;
END $$;
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_custody_original_retention_proposal_v110
    (id,tenant_id,file_id,proposed_by,retention_until,reason)
  VALUES('@proposal@','@tenant@','@file@','@uploader@',
   now()+INTERVAL '2 years','Uploader cannot propose his own retention');
  RAISE EXCEPTION 'V207 uploader self-proposal accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
END $$;
INSERT INTO finance_custody_original_retention_proposal_v110
 (id,tenant_id,file_id,proposed_by,retention_until,reason)
VALUES('@proposal@','@tenant@','@file@','@proposer@',
 now()+INTERVAL '2 years','Retain private operator content while holding originals');
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_custody_original_governance_v110
  WHERE file_id='@file@' AND governance_state='RETENTION_PENDING_HOLD_REMAINS'
    AND legal_hold AND NOT deletion_permitted) THEN
  RAISE EXCEPTION 'V207 proposal unexpectedly released legal hold';
 END IF;
 BEGIN
  INSERT INTO finance_custody_original_retention_review_v110
    (id,tenant_id,proposal_id,reviewed_by,decision,reason)
  VALUES('@review@','@tenant@','@proposal@','@proposer@',
   'REVIEWED_NO_PURGE','Proposer must not self-review retention');
  RAISE EXCEPTION 'V207 proposer self-review accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
 BEGIN
  INSERT INTO finance_custody_original_retention_review_v110
    (id,tenant_id,proposal_id,reviewed_by,decision,reason)
  VALUES('@review@','@tenant@','@proposal@','@uploader@',
   'REVIEWED_NO_PURGE','Uploader must not review his own evidence');
  RAISE EXCEPTION 'V207 uploader self-review accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
END $$;
INSERT INTO finance_custody_original_retention_review_v110
 (id,tenant_id,proposal_id,reviewed_by,decision,reason)
VALUES('@review@','@tenant@','@proposal@','@reviewer@',
 'REVIEWED_NO_PURGE','Independent review leaves legal hold in effect');
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_custody_original_governance_v110
  WHERE file_id='@file@' AND governance_state='RETENTION_REVIEWED_HOLD_REMAINS'
   AND legal_hold AND NOT deletion_permitted
   AND NOT operator_identity_verified AND NOT physical_handoff_verified
   AND NOT actual_batch_cogs_verified AND realized_batch_cogs IS NULL) THEN
  RAISE EXCEPTION 'V207 reviewer elevated external truth or deletion';
 END IF;
END $$;
INSERT INTO finance_custody_original_metadata_access_v110
 (id,tenant_id,file_id,accessed_by,access_purpose,snapshot_sha256,
  snapshot_size_bytes)
VALUES('@other@','@tenant@','@file@','@reviewer@',
 'PRIVATE_METADATA_REVIEW','@png_hash@',150000);
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_custody_original_metadata_access_v110
  WHERE id='@other@' AND snapshot_sha256='@pdf_hash@'
    AND snapshot_size_bytes=@pdf_size@ AND NOT original_bytes_returned) THEN
  RAISE EXCEPTION 'V207 DB failed to capture actual original metadata';
 END IF;
 BEGIN
  INSERT INTO finance_custody_original_metadata_access_v110
   (id,tenant_id,file_id,accessed_by,access_purpose,snapshot_sha256,
    snapshot_size_bytes,original_bytes_returned)
  VALUES('@otherreview@','@tenant@','@file@','@reviewer@',
   'PRIVATE_METADATA_REVIEW','@png_hash@',5,TRUE);
  RAISE EXCEPTION 'V207 fabricated byte disclosure accepted';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
END $$;
UPDATE finance_dispatch_custody_integrity_v108
 SET integrity_state='SIGNED_DISPATCH_STALE' WHERE dossier_id='@dossier@';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_custody_original_governance_v110
  WHERE file_id='@file@' AND governance_state='EVIDENCE_STALE_OR_UNVERIFIED'
   AND legal_hold AND NOT deletion_permitted) THEN
  RAISE EXCEPTION 'V207 upstream source revocation not propagated';
 END IF;
 BEGIN
  INSERT INTO finance_custody_original_retention_proposal_v110
   (id,tenant_id,file_id,proposed_by,retention_until,reason)
  VALUES('@otherproposal@','@tenant@','@carrier@','@proposer@',
   now()+INTERVAL '3 years','Stale carrier original cannot be approved');
  RAISE EXCEPTION 'V207 stale upstream source accepted retention';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
 BEGIN
  INSERT INTO finance_custody_original_retention_review_v110
    (id,tenant_id,proposal_id,reviewed_by,decision,reason)
  VALUES('@otherreview@','@tenant@','@proposal@','@reviewer@',
   'REJECTED','Duplicate review must remain forbidden');
  RAISE EXCEPTION 'V207 duplicate independent review accepted';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;
 BEGIN
  INSERT INTO finance_custody_original_metadata_access_v110
    (id,tenant_id,file_id,accessed_by,access_purpose,snapshot_sha256,
     snapshot_size_bytes)
  VALUES('@otherreview@','@other_tenant@','@file@','@reviewer@',
   'PRIVATE_METADATA_REVIEW','@png_hash@',5);
  RAISE EXCEPTION 'V207 cross tenant original metadata accepted';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
END $$;
DO $$ BEGIN
 BEGIN
  UPDATE finance_custody_original_retention_proposal_v110
   SET retention_until=now()+INTERVAL '1 year' WHERE id='@proposal@';
  RAISE EXCEPTION 'V207 retention proposal mutated';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%cannot be changed or deleted%' THEN RAISE; END IF;
 END;
 BEGIN
  DELETE FROM finance_custody_original_retention_review_v110 WHERE id='@review@';
  RAISE EXCEPTION 'V207 review deleted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%cannot be changed or deleted%' THEN RAISE; END IF;
 END;
 BEGIN
  DELETE FROM finance_custody_original_metadata_access_v110 WHERE id='@other@';
  RAISE EXCEPTION 'V207 metadata audit deleted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%cannot be changed or deleted%' THEN RAISE; END IF;
 END;
END $$;
ROLLBACK;
"""
for k,v in ids.items(): sql=sql.replace("@"+k+"@",v)
for k,v in {"pdf_hash":hpdf,"png_hash":hpng,"pdf_hex":pdf.hex(),
             "png_hex":png.hex(),"pdf_size":str(len(pdf))}.items():
    sql=sql.replace("@"+k+"@",v)
if re.search(r"@[a-z0-9_]+@",sql):
    raise SystemExit("V207 missing fixture substitution")
proc=subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
 "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
 input=sql,text=True,capture_output=True,timeout=120,check=False)
if proc.returncode:
    raise SystemExit("V207 PG18 original-source failure:\n"+proc.stderr[-4500:])
print("PASS V207 PG18 V110 exact guards and source view: default legal hold, "
      "independent proposal and review, metadata SHA/size audit, upstream "
      "revocation, cross-tenant, append-only history, hard false deletion and "
      "external COGS; synthetic TEMP/ROLLBACK only")
