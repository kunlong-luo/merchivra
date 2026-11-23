#!/usr/bin/env python3
"""V205 real PostgreSQL 18 Flyway V108 functions + original source view.

Only disposable TEMP fixtures; no actual operator, carrier, Amazon, inventory
or financial evidence is obtained, verified, posted or backfilled.
"""
import os
import re
import subprocess
from pathlib import Path

if os.environ.get("MERCHIVRA_V205_DISPOSABLE_ONLY")!="true":
    raise SystemExit("V205 requires explicit disposable PG18 opt-in")
migration=Path("apps/server/src/main/resources/db/migration/"
               "V108__dispatch_custody_external_evidence_dossier.sql").read_text()
marker="CREATE VIEW finance_dispatch_custody_integrity_v108 AS\n"
end="\nCREATE FUNCTION finance_dispatch_custody_review_guard_v108"
if migration.count(marker)!=1 or migration.count(end)!=1:
    raise SystemExit("V205 original integrity view markers unavailable")
original=migration.split(marker,1)[1].split(end,1)[0]
if not original.rstrip().endswith(";"):
    raise SystemExit("V205 integrity view is truncated")
def uid(n):
    return "00000000-0000-4000-8000-"+f"{n:012x}"
ids={key:uid(0xE500+i) for i,key in enumerate([
    "tenant","other_tenant","mp","order","source","warehouse","claim",
    "claim2","dispatch","dispatch2","dossier","dossier2","review",
    "submitter","importer","registrar","reviewer","other"
])}
hash1="a"*64
hash2="b"*64
hash3="c"*64
hash4="d"*64
sql="""
BEGIN;
DO $$ BEGIN
 IF current_setting('server_version_num')::int<180000 THEN
  RAISE EXCEPTION 'V205 PG18 required';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history
    WHERE version='108' AND success) THEN
  RAISE EXCEPTION 'V108 Flyway must be installed before V205 smoke';
 END IF;
END $$;
CREATE TEMP TABLE finance_sale_lot_signed_dispatch_v107 (
 id UUID,tenant_id UUID,claim_id UUID,source_id UUID,warehouse_id UUID,
 marketplace_id UUID,sales_order_id UUID,external_dispatch_ref TEXT,
 canonical_sha256 CHAR(64),quantity INTEGER,observed_at TIMESTAMPTZ,
 recorded_by UUID
) ON COMMIT DROP;
CREATE TEMP TABLE finance_sale_lot_dispatch_integrity_v107 (
 id UUID,tenant_id UUID,claim_id UUID,source_id UUID,warehouse_id UUID,
 marketplace_id UUID,sales_order_id UUID,external_dispatch_ref TEXT,
 canonical_sha256 CHAR(64),quantity INTEGER,evidence_state TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE finance_wms_signing_source_v98 (
 id UUID,tenant_id UUID,warehouse_id UUID,state TEXT,requested_by UUID
) ON COMMIT DROP;
CREATE TEMP TABLE finance_dispatch_custody_dossier_v108 (
 LIKE public.finance_dispatch_custody_dossier_v108
 INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES
) ON COMMIT DROP;
CREATE TEMP TABLE finance_dispatch_custody_review_v108 (
 LIKE public.finance_dispatch_custody_review_v108
 INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES
) ON COMMIT DROP;
CREATE TRIGGER v205_dossier_real_guard
 BEFORE INSERT ON finance_dispatch_custody_dossier_v108
 FOR EACH ROW EXECUTE FUNCTION public.finance_dispatch_custody_capture_v108();
CREATE TRIGGER v205_dossier_real_immutable
 BEFORE UPDATE OR DELETE ON finance_dispatch_custody_dossier_v108
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_append_only_v98();
CREATE TRIGGER v205_review_real_guard
 BEFORE INSERT ON finance_dispatch_custody_review_v108
 FOR EACH ROW EXECUTE FUNCTION public.finance_dispatch_custody_review_guard_v108();
CREATE TRIGGER v205_review_real_immutable
 BEFORE UPDATE OR DELETE ON finance_dispatch_custody_review_v108
 FOR EACH ROW EXECUTE FUNCTION public.finance_wms_append_only_v98();
"""
sql+="CREATE TEMP VIEW finance_dispatch_custody_integrity_v108 AS\n"+original+"\n"
sql+="""
INSERT INTO finance_wms_signing_source_v98 VALUES (
 '@source@','@tenant@','@warehouse@','APPROVED','@registrar@');
INSERT INTO finance_sale_lot_signed_dispatch_v107 VALUES
 ('@dispatch@','@tenant@','@claim@','@source@','@warehouse@',
  '@mp@','@order@','DISPATCH-205-A','@hash1@',2,
  now()-INTERVAL '2 hours','@importer@'),
 ('@dispatch2@','@tenant@','@claim2@','@source@','@warehouse@',
  '@mp@','@order@','DISPATCH-205-B','@hash2@',2,
  now()-INTERVAL '2 hours','@importer@');
INSERT INTO finance_sale_lot_dispatch_integrity_v107 VALUES
 ('@dispatch@','@tenant@','@claim@','@source@','@warehouse@',
  '@mp@','@order@','DISPATCH-205-A','@hash1@',2,
  'TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED'),
 ('@dispatch2@','@tenant@','@claim2@','@source@','@warehouse@',
  '@mp@','@order@','DISPATCH-205-B','@hash2@',2,
  'TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED');

-- An invalid or absent independent source must not enter the dossier.
UPDATE finance_wms_signing_source_v98 SET state='REVOKED'
 WHERE id='@source@';
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_dispatch_custody_dossier_v108 (
   id,tenant_id,marketplace_id,sales_order_id,signed_dispatch_id,
   operator_registry_ref,operator_document_sha256,carrier_handoff_ref,
   carrier_document_sha256,claimed_handoff_at,submitted_by)
  VALUES ('@dossier@','@tenant@','@mp@','@order@','@dispatch@',
   'OPERATOR-205','@hash3@','HANDOFF-205','@hash4@',now(),'@submitter@');
  RAISE EXCEPTION 'V205 accepted revoked WMS source';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
END $$;
UPDATE finance_wms_signing_source_v98 SET state='APPROVED'
 WHERE id='@source@';

-- Server-side trigger overwrites fabricated client snapshots.
INSERT INTO finance_dispatch_custody_dossier_v108 (
 id,tenant_id,marketplace_id,sales_order_id,signed_dispatch_id,
 snapshot_claim_id,snapshot_source_id,snapshot_warehouse_id,
 snapshot_dispatch_ref,snapshot_dispatch_sha256,snapshot_quantity,
 operator_registry_ref,operator_document_sha256,carrier_handoff_ref,
 carrier_document_sha256,claimed_handoff_at,submitted_by)
VALUES ('@dossier@','@tenant@','@mp@','@order@','@dispatch@',
 '@other@','@other@','@other@','FAKE-DISPATCH','@hash4@',999,
 'OPERATOR-205','@hash3@','HANDOFF-205','@hash4@',now(),'@submitter@');
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_dispatch_custody_dossier_v108
  WHERE id='@dossier@' AND snapshot_claim_id='@claim@'
   AND snapshot_source_id='@source@' AND snapshot_warehouse_id='@warehouse@'
   AND snapshot_dispatch_sha256='@hash1@' AND snapshot_quantity=2
   AND snapshot_dispatch_ref='DISPATCH-205-A'
   AND NOT operator_identity_verified AND NOT physical_handoff_verified
   AND NOT actual_batch_cogs_verified AND NOT cogs_posted) THEN
  RAISE EXCEPTION 'V205 canonical DB capture or hard false flags broken';
 END IF;
END $$;

-- Independent review refuses submitter, signing importer, source registrar.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_dispatch_custody_review_v108(
   id,tenant_id,dossier_id,reviewed_by,decision,reason)
  VALUES('@review@','@tenant@','@dossier@','@submitter@',
    'DOCS_CONSISTENT_UNVERIFIED','Self-review should never pass');
  RAISE EXCEPTION 'V205 permitted dossier self review';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
 BEGIN
  INSERT INTO finance_dispatch_custody_review_v108(
   id,tenant_id,dossier_id,reviewed_by,decision,reason)
  VALUES('@review@','@tenant@','@dossier@','@importer@',
    'DOCS_CONSISTENT_UNVERIFIED','Importing signer review should fail');
  RAISE EXCEPTION 'V205 permitted WMS importing actor to review';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
 BEGIN
  INSERT INTO finance_dispatch_custody_review_v108(
   id,tenant_id,dossier_id,reviewed_by,decision,reason)
  VALUES('@review@','@tenant@','@dossier@','@registrar@',
    'DOCS_CONSISTENT_UNVERIFIED','Public key registrar cannot self review');
  RAISE EXCEPTION 'V205 permitted WMS key registrar to review';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
END $$;
INSERT INTO finance_dispatch_custody_review_v108 (
 id,tenant_id,dossier_id,reviewed_by,decision,reason)
VALUES ('@review@','@tenant@','@dossier@','@reviewer@',
 'DOCS_CONSISTENT_UNVERIFIED','Document references only checked internally');
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_dispatch_custody_integrity_v108
  WHERE dossier_id='@dossier@'
    AND integrity_state='INTERNAL_REVIEW_ONLY_EXTERNAL_UNVERIFIED'
    AND NOT operator_identity_verified AND NOT physical_handoff_verified
    AND NOT platform_fulfillment_verified AND NOT actual_batch_cogs_verified
    AND realized_batch_cogs IS NULL) THEN
  RAISE EXCEPTION 'V205 internal review improperly certifies external reality';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM finance_dispatch_custody_review_v108
  WHERE dossier_id='@dossier@'
    AND snapshot_operator_document_sha256='@hash3@'
    AND snapshot_carrier_document_sha256='@hash4@'
    AND NOT operator_identity_verified AND NOT physical_handoff_verified
    AND NOT cogs_posted) THEN
  RAISE EXCEPTION 'V205 review snapshots not captured in database';
 END IF;
END $$;

-- Physical observation can go stale after review.
UPDATE finance_sale_lot_dispatch_integrity_v107
 SET evidence_state='SIGNING_SOURCE_REVOKED'
 WHERE id='@dispatch@';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_dispatch_custody_integrity_v108
  WHERE dossier_id='@dossier@' AND integrity_state='SIGNED_DISPATCH_STALE')
 THEN RAISE EXCEPTION 'V205 did not invalidate revoked upstream signature'; END IF;
END $$;
UPDATE finance_sale_lot_dispatch_integrity_v107
 SET evidence_state='TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED'
 WHERE id='@dispatch@';
UPDATE finance_sale_lot_dispatch_integrity_v107
 SET canonical_sha256='@hash4@' WHERE id='@dispatch@';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM finance_dispatch_custody_integrity_v108
  WHERE dossier_id='@dossier@' AND integrity_state='CUSTODY_SNAPSHOT_STALE')
 THEN RAISE EXCEPTION 'V205 did not detect signed payload identity drift'; END IF;
END $$;
UPDATE finance_sale_lot_dispatch_integrity_v107 SET canonical_sha256='@hash1@'
 WHERE id='@dispatch@';

-- Same carrier handoff ref may cover multiple claims; conflicting claimed
-- original document SHA under the same ref must HOLD BOTH historical dossiers.
INSERT INTO finance_dispatch_custody_dossier_v108 (
 id,tenant_id,marketplace_id,sales_order_id,signed_dispatch_id,
 operator_registry_ref,operator_document_sha256,carrier_handoff_ref,
 carrier_document_sha256,claimed_handoff_at,submitted_by)
VALUES ('@dossier2@','@tenant@','@mp@','@order@','@dispatch2@',
 'OPERATOR-205','@hash3@','HANDOFF-205','@hash1@',now(),'@submitter@');
DO $$ BEGIN
 IF (SELECT count(*) FROM finance_dispatch_custody_integrity_v108
  WHERE integrity_state='EXTERNAL_REFERENCE_DIGEST_CONFLICT')<>2 THEN
  RAISE EXCEPTION 'V205 conflicting reused carrier manifest not detected';
 END IF;
 BEGIN
  INSERT INTO finance_dispatch_custody_review_v108 (
    id,tenant_id,dossier_id,reviewed_by,decision,reason)
  VALUES('@other@','@tenant@','@dossier2@','@reviewer@',
   'DOCS_CONSISTENT_UNVERIFIED','Conflicting original cannot be approved');
  RAISE EXCEPTION 'V205 permitted internally consistent review on manifest conflict';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL;
 END;
END $$;
INSERT INTO finance_dispatch_custody_review_v108 (
 id,tenant_id,dossier_id,reviewed_by,decision,reason)
VALUES ('@other@','@tenant@','@dossier2@','@reviewer@',
 'NEEDS_EVIDENCE','Conflicting original carrier document digest');

-- Already used dispatch and same dossier cannot be reassigned.
DO $$ BEGIN
 BEGIN
  INSERT INTO finance_dispatch_custody_dossier_v108 (
   id,tenant_id,marketplace_id,sales_order_id,signed_dispatch_id,
   operator_registry_ref,operator_document_sha256,carrier_handoff_ref,
   carrier_document_sha256,claimed_handoff_at,submitted_by)
  VALUES ('@other@','@tenant@','@mp@','@order@','@dispatch@',
   'OPERATOR-205','@hash3@','HANDOFF-206','@hash4@',now(),'@submitter@');
  RAISE EXCEPTION 'V205 allowed duplicate dossier for same dispatch';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
  INSERT INTO finance_dispatch_custody_dossier_v108 (
   id,tenant_id,marketplace_id,sales_order_id,signed_dispatch_id,
   operator_registry_ref,operator_document_sha256,carrier_handoff_ref,
   carrier_document_sha256,claimed_handoff_at,submitted_by,
   operator_identity_verified)
  VALUES ('@other@','@tenant@','@mp@','@order@','@dispatch2@',
   'OPERATOR-205','@hash3@','HANDOFF-206','@hash4@',now(),'@submitter@',TRUE);
  RAISE EXCEPTION 'V205 fabricated B2 external identity accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
 BEGIN
  INSERT INTO finance_dispatch_custody_dossier_v108 (
   id,tenant_id,marketplace_id,sales_order_id,signed_dispatch_id,
   operator_registry_ref,operator_document_sha256,carrier_handoff_ref,
   carrier_document_sha256,claimed_handoff_at,submitted_by)
  VALUES ('@other@','@other_tenant@','@mp@','@order@','@dispatch@',
   'OPERATOR-205','@hash3@','HANDOFF-206','@hash4@',now(),'@submitter@');
  RAISE EXCEPTION 'V205 allowed another tenant to borrow dispatch';
 EXCEPTION WHEN SQLSTATE '23514' THEN NULL; END;
END $$;

DO $$ BEGIN
 BEGIN
  UPDATE finance_dispatch_custody_dossier_v108
   SET operator_registry_ref='FORGED-OPERATOR' WHERE id='@dossier@';
  RAISE EXCEPTION 'V205 dossier mutated';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%cannot be changed or deleted%' THEN RAISE; END IF;
 END;
 BEGIN
  DELETE FROM finance_dispatch_custody_review_v108 WHERE id='@review@';
  RAISE EXCEPTION 'V205 review deleted';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%cannot be changed or deleted%' THEN RAISE; END IF;
 END;
END $$;
ROLLBACK;
"""
for key,value in ids.items():
    sql=sql.replace("@"+key+"@",value)
for k,v in {"hash1":hash1,"hash2":hash2,"hash3":hash3,"hash4":hash4}.items():
    sql=sql.replace("@"+k+"@",v)
if re.search(r"@[a-z0-9_]+@",sql):
    raise SystemExit("V205 incomplete disposable fixture substitution")
proc=subprocess.run(["psql","-X","-h","127.0.0.1","-U","merchivra",
                      "-d","merchivra","-v","ON_ERROR_STOP=1","-q"],
                    input=sql,text=True,capture_output=True,
                    timeout=120,check=False)
if proc.returncode:
    raise SystemExit("V205 exact PG18 Flyway failure:\n"+proc.stderr[-5000:])
print("PASS V205 PG18 V108 original capture/review guards and source view: "
      "DB-owned snapshots, reviewer independence, revoked signed-source, "
      "manifest digest conflict, immutable forensic records, tenant isolation "
      "and hard false operator/physical/platform/COGS flags; TEMP/ROLLBACK only")
