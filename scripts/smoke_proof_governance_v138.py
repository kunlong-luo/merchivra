#!/usr/bin/env python3
"""V138 true PG18/Owner HTTP for private original governance, no deletion.

Creates only invented evidence attached to the existing V95 synthetic action
inside the disposable GitHub full-suite PG18, then leaves it for automatic
destruction of the disposable DB. Never deletes originals, posts stock or
calls Amazon. Product SQL has no deletion route/job.
"""
import hashlib
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.request import Request, HTTPCookieProcessor, build_opener
from urllib.parse import urlencode
from uuid import uuid4

assert os.getenv("DB_HOST") == "127.0.0.1"
assert os.getenv("DB_NAME") == "merchivra"
assert os.getenv("MERCHIVRA_DEMO_ENABLED") == "true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED") == "false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES","false") == "false"

http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
root="http://127.0.0.1:8080"

def sql(query):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    r=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
        env=env,text=True,capture_output=True,check=False)
    assert r.returncode==0,(query[:180],r.stderr[-1100:])
    return r.stdout.strip()

def call(path,status=200,method="GET",body=None,csrf=None):
    headers={"Accept":"application/json"}
    if csrf:headers["X-CSRF-TOKEN"]=csrf
    if body is not None:
        if path=="/api/v1/auth/login":
            headers["Content-Type"]="application/x-www-form-urlencoded"
            data=urlencode(body).encode()
        else:
            headers["Content-Type"]="application/json"
            data=json.dumps(body).encode()
    else:data=None
    try:
        with http.open(Request(root+path,data=data,headers=headers,method=method),
            timeout=50) as response:
            actual,raw,cache=response.status,response.read(),response.headers.get("Cache-Control","")
    except HTTPError as err:
        actual,raw,cache=err.code,err.read(),""
    assert actual==status,(path,actual,status,raw[:800])
    if status!=200:return None
    if path.startswith("/api/v1/purchasing/lot-proof-governance"):
        assert "no-store" in cache,(path,cache)
    return json.loads(raw)

probe=str(uuid4())
call("/api/v1/purchasing/lot-proof-governance/"+probe,401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login",method="POST",body={
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},csrf=csrf)
# Authentication rotates/migrates the session's CSRF token. All protected
# governance POSTs MUST use the post-login token, not the anonymous one.
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me")
assert me["isOwner"]
tenant=me["activeTenantId"]
actor=me["userId"]
base="/api/v1/purchasing/lot-proof-governance"
source=sql(f"""SELECT id,source_warehouse_id FROM erp_local_lot_action
 WHERE tenant_id='{tenant}' AND reason='V95 synthetic observed surplus only'
 ORDER BY created_at DESC,id DESC LIMIT 1""").split("|")
assert len(source)==2,source
action,warehouse=source

disposition,proof,file=(str(uuid4()),str(uuid4()),str(uuid4()))
content=b"%PDF-1.7\nV138 synthetic isolated governance-only fixture\n%%EOF"
digest=hashlib.sha256(content).hexdigest()
before=sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")
# This fixture deliberately cannot be used as proof of actual disposal,
# supplier authenticity, or warehouse stock movement.
created=sql(f"""BEGIN;
 INSERT INTO erp_local_lot_disposition(
 id,tenant_id,request_key,quarantine_action_id,warehouse_id,
 quantity,method,status,reason,proposed_by)
 VALUES('{disposition}','{tenant}',gen_random_uuid(),'{action}',
 '{warehouse}',1,'DISPOSAL','PENDING_APPROVAL',
 'V138 synthetic metadata only, not physical disposal','{actor}');
 INSERT INTO erp_local_lot_disposition_proof(
 id,tenant_id,request_key,disposition_id,proof_kind,evidence_reference,
 explanation,submitted_by)
 VALUES('{proof}','{tenant}',gen_random_uuid(),'{disposition}',
 'DISPOSAL_CERTIFICATE','V138-SYNTHETIC-NOT-REAL',
 'V138 fake parent proof for disposal-safe governance HTTP',
 '{actor}');
 INSERT INTO erp_local_lot_disposition_proof_file(
 id,tenant_id,proof_id,request_key,media_type,bytes,size_bytes,sha256,uploaded_by)
 VALUES('{file}','{tenant}','{proof}',gen_random_uuid(),'application/pdf',
 decode('{content.hex()}','hex'),{len(content)},'{digest}','{actor}');
 COMMIT;
 SELECT count(*) FROM erp_local_lot_disposition_proof_file
 WHERE tenant_id='{tenant}' AND id='{file}';""")
assert created=="1",created
status=call(base+"/"+proof)
assert status["proofId"]==proof and status["fileId"]==file
assert not status["legalHold"] and status["retentionUntil"] is None
assert status["disposalState"]=="NONE"
call(base+"/"+str(uuid4()),404)

def operate(action_name,status_code=200,extra=None):
    payload={"action":action_name,"reason":
        "V138 synthetic policy review for local disposable test only"}
    payload.update(extra or {})
    return call(base+"/"+proof+"/commands",status_code,
                method="POST",body=payload,csrf=csrf)

operate("REQUEST_DISPOSAL",409) # never default an unset policy to eligible
held=operate("PLACE_HOLD")
assert held["legalHold"] and held["disposalState"]=="NONE"
release=operate("REQUEST_HOLD_RELEASE")
assert release["holdReleaseRequestedBy"]==actor and release["legalHold"]
operate("APPROVE_HOLD_RELEASE",409) # self-approval forbidden
proposed=operate("PROPOSE_RETENTION",extra={
    "retentionUntil":"2050-01-01T00:00:00Z"})
assert proposed["retentionUntil"] is None
assert proposed["retentionProposedUntil"].startswith("2050-01-01")
operate("APPROVE_RETENTION",409) # self-approval forbidden
operate("REQUEST_DISPOSAL",409) # hold + missing approved retention
operate("REVIEW_DISPOSAL",409)
operate("DROP_ORIGINAL",400)

cap=call(base+"/capacity")
assert cap["fileCount"]>=1 and cap["originalBytes"]>=len(content)
assert cap["heldFiles"]>=1 and cap["retentionUnsetFiles"]>=1
assert cap["pendingRetentionFiles"]>=1
assert cap["reviewedDisposalFiles"]==0
assert sql(f"""SELECT count(*) FROM erp_lot_proof_file_governance_audit
 WHERE tenant_id='{tenant}' AND proof_id='{proof}'""")=="3"
assert sql(f"""SELECT sha256 FROM erp_local_lot_disposition_proof_file
 WHERE tenant_id='{tenant}' AND id='{file}'""")==digest
assert sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")==before
print("PASS V138 true PG18 HTTP: original SHA unchanged, policy unset blocks "+
      "disposal, hold immediate, release and retention proposals audited, "+
      "same-Owner approvals 409, tenant capacity safe, no original DELETE "+
      "or signed stock writes; fixture only in disposable CI DB")
