#!/usr/bin/env python3
"""V83 disposable PostgreSQL18 HTTP preflight + persisted batch audit regression.
Run only after V76-V82 fictional scripts; no real Amazon/SP-API writes.
"""
import http.cookiejar,json,os,subprocess
from urllib.parse import urlencode
from urllib.error import HTTPError
from urllib.request import Request,HTTPCookieProcessor,build_opener
from uuid import uuid4
assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=""
def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    r=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
     "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",statement],
     capture_output=True,text=True,env=env,check=False)
    assert r.returncode==0,r.stderr[-1000:]
    return r.stdout.strip()
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method=="POST" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,
          method=method,data=data,headers=headers),timeout=30) as response:
            status,payload=response.status,response.read()
    except HTTPError as exc:
        status,payload=exc.code,exc.read()
    assert status==expected,(method,path,status,expected,payload[:900])
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
foreign=str(uuid4())
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation/workbench/batches",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
tenant=call("/api/v1/auth/me")["activeTenantId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp,"V76-V82 fixtures required"
root=f"/api/v1/fba/marketplaces/{mp}/purchase-reconciliation/workbench"
preflight=root+"/bulk-resolve/preflight"
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation/workbench/batches",expected=404)
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation/workbench/bulk-resolve/preflight",
     "POST",{"lines":[{"lineId":str(uuid4()),"expectedVersion":0,
                      "expectedFingerprint":"a"*64}]},404)
batches=call(root+"/batches")
assert 1<=len(batches)<=50,batches
assert batches==sorted(batches,key=lambda row:row["createdAt"],reverse=True)
assert any(b["actionType"]=="RESOLVE" and b["affectedCount"]==1 for b in batches)
assert any(b["actionType"]=="ASSIGN" and b["affectedCount"]==2 for b in batches)
assert all(b["requestKey"] and b["actorId"] and b["createdAt"] for b in batches)

rows={x["shipmentReference"]:x for x in call(root)["items"]}
resolved=rows["V76-UNLINKED-DRAFT"]
pending=rows["V76-IDENTITY-PENDING"]
confirmed=rows["V76-IDENTITY-CONFIRMED"]
assert resolved["state"]=="RESOLVED" and confirmed["state"]=="OPEN"
def choice(row):
    return {"lineId":row["lineId"],"expectedVersion":row["version"],
            "expectedFingerprint":row["fingerprint"]}
call(preflight,"POST",{"lines":[]},400)
call(preflight,"POST",{"lines":[choice(pending),choice(pending)]},400)
call(preflight,"POST",{"lines":[{**choice(pending),"expectedFingerprint":"bad"}]},400)
call(preflight,"POST",{"lines": [choice(pending)] * 51},400)
out=call(preflight,"POST",{"lines":[choice(resolved),choice(pending),
                     {"lineId":str(uuid4()),"expectedVersion":0,
                      "expectedFingerprint":"a"*64}]})
assert out["selectedCount"]==3 and out["eligibleCount"]==0 and not out["allEligible"],out
assert out["amazonVerified"] is False and out["fbaStockCredited"] is False
assert out["lines"][0]["reasonCode"]=="NOT_ACKNOWLEDGED",out
assert out["lines"][1]["reasonCode"] in ("EVIDENCE_CHANGED",
                                        "IDENTITY_REVIEW_REQUIRED"),out
assert out["lines"][2]["reasonCode"]=="OUT_OF_SCOPE",out
assert out["lines"][2]["shipmentReference"] is None and out["lines"][2]["sellerSku"] is None

before_audit=sql(f"SELECT count(*) FROM fba_purchase_exception_audit WHERE tenant_id='{tenant}'")
before_batches=sql(f"SELECT count(*) FROM fba_purchase_exception_batch WHERE tenant_id='{tenant}'")
before_cases=sql(f"SELECT count(*) FROM fba_purchase_exception_case WHERE tenant_id='{tenant}'")
assert call(preflight,"POST",{"lines":[{**choice(resolved),"expectedVersion":0}]})["lines"][0]["reasonCode"]=="VERSION_CONFLICT"
assert sql(f"SELECT count(*) FROM fba_purchase_exception_audit WHERE tenant_id='{tenant}'")==before_audit
assert sql(f"SELECT count(*) FROM fba_purchase_exception_batch WHERE tenant_id='{tenant}'")==before_batches
assert sql(f"SELECT count(*) FROM fba_purchase_exception_case WHERE tenant_id='{tenant}'")==before_cases
# A reviewer can acknowledge new source evidence first, then preview READY.
updated=call(f"{root}/cases/{confirmed['lineId']}/actions","POST",{
 "action":"ACKNOWLEDGE","expectedVersion":confirmed["version"],
 "expectedFingerprint":confirmed["fingerprint"],
 "note":"V83 owner explicitly reviewed latest local PO and FBA source changes"})
assert updated["state"]=="ACKNOWLEDGED" and not updated["stale"],updated
assert updated["assigneeId"] is not None
ready=call(preflight,"POST",{"lines":[choice(updated)]})
assert ready["allEligible"] and ready["eligibleCount"]==1,ready
assert ready["lines"][0]["reasonCode"]=="READY",ready
assert ready["lines"][0]["currentVersion"]==updated["version"]
# Concurrent source change after a successful preview must fail closed at
# the actual V82 transaction boundary, not treat a preview as approval.
asn=sql(f"""SELECT asn_id FROM fba_inbound_purchase_link
 WHERE tenant_id='{tenant}' AND shipment_id='{updated['shipmentId']}'
 AND state='CONFIRMED'""")
assert asn
sql(f"""UPDATE erp_purchase_asn SET status='CANCELLED'
 WHERE tenant_id='{tenant}' AND id='{asn}'""")
changed=call(preflight,"POST",{"lines":[choice(updated)]})
assert not changed["allEligible"] and changed["lines"][0]["reasonCode"]=="EVIDENCE_CHANGED",changed
audit_before=sql(f"SELECT count(*) FROM fba_purchase_exception_audit WHERE tenant_id='{tenant}'")
batch_before=sql(f"SELECT count(*) FROM fba_purchase_exception_batch WHERE tenant_id='{tenant}'")
key=str(uuid4())
call(root+"/bulk-resolve","POST",{
 "requestKey":key,"confirmed":True,"lines":[choice(updated)],
 "note":"V83 preview was stale and must never become an approval"},409)
assert sql(f"SELECT count(*) FROM fba_purchase_exception_audit WHERE tenant_id='{tenant}'")==audit_before
assert sql(f"SELECT count(*) FROM fba_purchase_exception_batch WHERE tenant_id='{tenant}'")==batch_before
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="0"
print("PASS V83: Owner-only preview, per-line diagnostics, real V77/V82 batch ledger, concurrent source drift rejects original RESOLVE, no preview writes or inventory changes")
