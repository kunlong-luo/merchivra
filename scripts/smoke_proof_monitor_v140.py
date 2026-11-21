#!/usr/bin/env python3
"""V140 disposable PG18 actual Owner HTTP: 135+ immutable audit events, pages,
opt-in per-tenant payload warning; no deletion, no stock or Amazon writes."""
import base64
import hashlib
import http.cookiejar
import json
import os
import subprocess
from datetime import datetime
from urllib.error import HTTPError
from urllib.parse import urlencode, quote
from urllib.request import Request, HTTPCookieProcessor, build_opener
from uuid import UUID, uuid4

assert os.getenv("GITHUB_ACTIONS")=="true"
assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("DB_PASSWORD")=="ci_disposable_database_password"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES","false")=="false"
http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
base="http://127.0.0.1:8080"
root="/api/v1/purchasing/lot-proof-governance"

def sql(query):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    p=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
        env=env,text=True,capture_output=True,timeout=90,check=False)
    assert p.returncode==0,(query[:150],p.stderr[-800:])
    return p.stdout.strip()

def call(path,expected=200,method="GET",body=None,token=None):
    headers={"Accept":"application/json"}
    if token:headers["X-CSRF-TOKEN"]=token
    if body is not None:
        if path=="/api/v1/auth/login":
            headers["Content-Type"]="application/x-www-form-urlencoded"
            data=urlencode(body).encode()
        else:
            headers["Content-Type"]="application/json"
            data=json.dumps(body).encode()
    else:data=None
    try:
        with http.open(Request(base+path,data=data,headers=headers,method=method),
            timeout=40) as r:
            code,payload,cache=r.status,r.read(),r.headers.get("Cache-Control","")
    except HTTPError as e:
        code,payload,cache=e.code,e.read(),""
    assert code==expected,(path,code,expected,payload[:700])
    if code!=200:return None
    if path.startswith(root):assert "no-store" in cache,(path,cache)
    return json.loads(payload)

def cursor_parts(cursor):
    raw=base64.urlsafe_b64decode(cursor+"="*((-len(cursor))%4))
    return raw.decode("utf-8").split(chr(31))

def encode_parts(parts):
    return base64.urlsafe_b64encode(chr(31).join(parts).encode()).decode().rstrip("=")

call(root+"/capacity-alert",401)
call(root+"/"+str(uuid4())+"/history/page",401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login",method="POST",body={
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},token=csrf)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me")
assert me["isOwner"],me
tenant,actor=me["activeTenantId"],me["userId"]
assert str(UUID(tenant))==tenant and str(UUID(actor))==actor
original=sql(f"""
 SELECT p.id,f.id FROM erp_local_lot_disposition_proof p
 JOIN erp_local_lot_disposition_proof_file f
 ON f.tenant_id=p.tenant_id AND f.proof_id=p.id
 WHERE p.tenant_id='{tenant}'
 AND p.evidence_reference='V138-SYNTHETIC-NOT-REAL'
 ORDER BY p.created_at DESC LIMIT 1""").split("|")
assert len(original)==2,original
proof,file=original
assert str(UUID(proof))==proof and str(UUID(file))==file
before=sql(f"SELECT count(*) FROM erp_local_stock_movement WHERE tenant_id='{tenant}'")
sha_before=sql(f"""SELECT sha256 FROM erp_local_lot_disposition_proof_file
 WHERE tenant_id='{tenant}' AND id='{file}'""")

# Existing three V138 events, plus 135 synthetic nonposting audit events.
# A five-second timestamp bucket intentionally creates ties for the UUID
# keyset; all actions are immutable observations in disposable CI only.
sql(f"""
 INSERT INTO erp_lot_proof_file_governance_audit(
    id,tenant_id,proof_id,file_id,action,actor_user_id,reason,recorded_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{proof}'::uuid,'{file}'::uuid,
        'PLACE_HOLD','{actor}'::uuid,
        'V140 synthetic no-delete historic governance audit event',
        now()-(i%5)*interval '1 second'
 FROM generate_series(1,135) AS i
 """)
total=int(sql(f"""SELECT count(*) FROM erp_lot_proof_file_governance_audit
 WHERE tenant_id='{tenant}' AND proof_id='{proof}'"""))
assert total==138,total
seen=[]
cursor=None
for page in range(1,6):
    path=root+"/"+proof+"/history/page?limit=50"
    if cursor:path+="&cursor="+quote(cursor,safe="")
    result=call(path)
    assert result["pageSize"]==50
    assert 0<len(result["items"])<=50
    seen.extend(result["items"])
    if result["hasMore"]:
        assert result["nextCursor"]
        cursor=result["nextCursor"]
    else:
        assert result["nextCursor"] is None
        assert page==3,(page,len(seen))
        break
else:raise AssertionError("History keyset did not reach final page")
assert len(seen)==total and len({x["id"] for x in seen})==total
sorted_values=[(datetime.fromisoformat(x["recordedAt"].replace("Z","+00:00")),
                UUID(x["id"]).int) for x in seen]
assert all(a>b for a,b in zip(sorted_values,sorted_values[1:])), "Keyset is not stable"
assert all(x["actorUserId"]==actor for x in seen)
assert all(x["action"] in {"PLACE_HOLD","REQUEST_HOLD_RELEASE",
    "PROPOSE_RETENTION"} for x in seen)
call(root+"/"+proof+"/history/page?limit=0",400)
call(root+"/"+proof+"/history/page?limit=101",400)
call(root+"/"+proof+"/history/page?cursor=!invalid",400)
call(root+"/"+str(uuid4())+"/history/page",404)

# Fresh first page cursor bound to tenant, proof, action domain, timestamp+id.
first=call(root+"/"+proof+"/history/page?limit=50")
parts=cursor_parts(first["nextCursor"])
assert len(parts)==5 and parts[1]==tenant and parts[2]==proof
for index,value in [(0,"WRONG_DOMAIN"),(1,str(uuid4())),
                    (2,str(uuid4())),(3,"not-an-offset-date"),
                    (4,"not-a-uuid")]:
    damaged=parts.copy()
    damaged[index]=value
    call(root+"/"+proof+"/history/page?limit=50&cursor="+
        quote(encode_parts(damaged),safe=""),400)

alert=call(root+"/capacity-alert")
assert alert["advisoryOnly"] and alert["status"]=="UNCONFIGURED"
assert alert["warningBytes"] is None and alert["fileCount"]>=1

def change(action,warning=None,expected=200):
    body={"action":action,"reason":
          "V140 synthetic Owner-only advisory capacity alert configuration"}
    if warning is not None:body["warningBytes"]=warning
    return call(root+"/capacity-threshold",expected,method="POST",
        body=body,token=csrf)

change("DISABLE",expected=409)
change("SET",1024,400)
change("SET",1099511627777,400)
change("DELETE_ORIGINAL",1048576,400)
normal=change("SET",1048576)
assert normal["status"]=="NORMAL" and normal["warningBytes"]==1048576
change("SET",1048576,409)

# Add a distinct synthetic source proof + exactly 1 MiB original bytea to
# exercise WARNING without ever modifying an existing immutable file.
source=sql(f"""
 SELECT a.id,a.source_warehouse_id FROM erp_local_lot_action a
 WHERE a.tenant_id='{tenant}' AND a.reason='V95 synthetic observed surplus only'
 AND NOT EXISTS(SELECT 1 FROM erp_local_lot_disposition d
   WHERE d.tenant_id=a.tenant_id AND d.quarantine_action_id=a.id)
 ORDER BY a.created_at DESC,a.id DESC LIMIT 1""").split("|")
assert len(source)==2,source
action,warehouse=source
disposition,proof2,file2=str(uuid4()),str(uuid4()),str(uuid4())
pdf=b"%PDF-"+b"A"*(1048576-5)
digest=hashlib.sha256(pdf).hexdigest()
sql(f"""
 BEGIN;
 INSERT INTO erp_local_lot_disposition(
    id,tenant_id,request_key,quarantine_action_id,warehouse_id,
    quantity,method,status,reason,proposed_by)
 VALUES('{disposition}','{tenant}',gen_random_uuid(),'{action}',
        '{warehouse}',1,'DISPOSAL','PENDING_APPROVAL',
        'V140 synthetic extra capacity document for offline CI only','{actor}');
 INSERT INTO erp_local_lot_disposition_proof(
    id,tenant_id,request_key,disposition_id,proof_kind,evidence_reference,
    explanation,submitted_by)
 VALUES('{proof2}','{tenant}',gen_random_uuid(),'{disposition}',
        'DISPOSAL_CERTIFICATE','V140-SYNTHETIC-CAPACITY',
        'V140 simulated PDF capacity, not real physical disposal','{actor}');
 INSERT INTO erp_local_lot_disposition_proof_file(
    id,tenant_id,proof_id,request_key,media_type,bytes,size_bytes,sha256,uploaded_by)
 VALUES('{file2}','{tenant}','{proof2}',gen_random_uuid(),'application/pdf',
        decode('255044462d','hex')||convert_to(repeat('A',1048571),'UTF8'),
        1048576,'{digest}','{actor}');
 COMMIT;""")
warning=call(root+"/capacity-alert")
assert warning["status"]=="WARNING" and warning["originalBytes"]>1048576
assert warning["warningBytes"]==1048576 and warning["fileCount"]>=2
gone=change("DISABLE")
assert gone["status"]=="UNCONFIGURED" and gone["warningBytes"] is None
assert call(root+"/capacity-alert")["status"]=="UNCONFIGURED"

assert int(sql(f"""SELECT count(*) FROM erp_lot_proof_file_capacity_threshold_audit
 WHERE tenant_id='{tenant}'"""))==2
assert sql(f"""SELECT sha256 FROM erp_local_lot_disposition_proof_file
 WHERE tenant_id='{tenant}' AND id='{file}'""")==sha_before
assert sql(f"""SELECT sha256 FROM erp_local_lot_disposition_proof_file
 WHERE tenant_id='{tenant}' AND id='{file2}'""")==digest
assert sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")==before
print("PASS V140 PG18 real HTTP: 138 proof-scoped audit events across 3 "+
      "stable keyset pages (UUID time ties), cursor domain/tenant/proof "+
      "400, Owner/no-store, tenant opt-in threshold unset->normal->warning"+
      "->disabled with 2 audit records, unchanged original SHA and signed stock"+
      ", no original DELETE, no live Amazon or external writes")
