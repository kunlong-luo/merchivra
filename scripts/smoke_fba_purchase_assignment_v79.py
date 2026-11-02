#!/usr/bin/env python3
"""V79 PG18 real HTTP regression; run after V76-V78 fictional fixtures.
Batch assignment reuses V77's idempotent ledger and per-row audit, no Amazon writes.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,HTTPCookieProcessor,build_opener
from uuid import uuid4
assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
base="http://127.0.0.1:8080"
csrf=""
def sql(query):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    out=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
      "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
      capture_output=True,text=True,env=env,check=False)
    assert out.returncode==0,out.stderr[-800:]
    return out.stdout.strip()
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method=="POST" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request(base+path,method=method,data=data,headers=headers),timeout=30) as res:
            status,payload=res.status,res.read()
    except HTTPError as err:
        status,payload=err.code,err.read()
    assert status==expected,(method,path,status,expected,payload[:500])
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
def post(path,body,expected=200):return call(path,"POST",body,expected)
foreign=str(uuid4())
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation/workbench/workload",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
  "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
  "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
tenant=call("/api/v1/auth/me")["activeTenantId"]
market=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert market
route=f"/api/v1/fba/marketplaces/{market}/purchase-reconciliation/workbench"
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation/workbench/workload",expected=404)
before=call(route+"/workload")
assert before["totalCases"]>=3 and len(before["owners"])>=2,before
rows={r["shipmentReference"]:r for r in call(route)["items"]}
unlinked=rows["V76-UNLINKED-DRAFT"]
confirmed=rows["V76-IDENTITY-CONFIRMED"]
assert unlinked["state"]=="ACKNOWLEDGED" and confirmed["state"]=="OPEN"
reviewer=sql("""SELECT u.id FROM app_user u
 JOIN local_credential c ON c.user_id=u.id
 WHERE c.login_name='v76-ci-independent-owner'""")
assert reviewer and any(x["userId"]==reviewer for x in before["owners"])
choices=[{"lineId":x["lineId"],"expectedVersion":x["version"],
          "expectedFingerprint":x["fingerprint"]} for x in [unlinked,confirmed]]
key=str(uuid4())
payload={"requestKey":key,"assigneeId":reviewer,"lines":choices,
         "note":"V79 allocate local FBA exception review to active owner"}
first=post(route+"/bulk-assign",payload)
assert first=={"requestKey":key,"assigneeId":reviewer,"assignedCount":2},first
assert post(route+"/bulk-assign",payload)==first
post(route+"/bulk-assign",{**payload,"note":"V79 conflicting request payload intentionally changed"},409)
post(route+"/bulk-assign",{**payload,"requestKey":str(uuid4()),
 "lines":[choices[0],choices[0]]},400)
post(route+"/bulk-assign",{**payload,"requestKey":str(uuid4()),
 "assigneeId":foreign},400)
updated={r["shipmentReference"]:r for r in call(route)["items"]}
for name,old in [("V76-UNLINKED-DRAFT",unlinked),
                 ("V76-IDENTITY-CONFIRMED",confirmed)]:
    item=updated[name]
    assert item["assigneeId"]==reviewer and item["version"]==old["version"]+1
    assert item["state"]==old["state"] and item["fingerprint"]==old["fingerprint"]
audit=sql(f"""SELECT count(*) FROM fba_purchase_exception_audit a
 JOIN fba_purchase_exception_case c ON c.id=a.case_id AND c.tenant_id=a.tenant_id
 WHERE c.tenant_id='{tenant}' AND c.seller_marketplace_id='{market}'
 AND c.inbound_line_id IN ('{unlinked['lineId']}','{confirmed['lineId']}')
 AND a.action='ASSIGN'""")
assert audit=="2",audit
# Stale versions abort all assignments and their ledger, no partial audit.
stale_key=str(uuid4())
post(route+"/bulk-assign",{**payload,"requestKey":stale_key},409)
assert sql(f"""SELECT count(*) FROM fba_purchase_exception_batch
 WHERE tenant_id='{tenant}' AND request_key='{stale_key}'""")=="0"
assert sql(f"""SELECT count(*) FROM fba_purchase_exception_audit
 WHERE tenant_id='{tenant}' AND action='ASSIGN'""")=="3"
loads=call(route+"/workload")
selected=next(x for x in loads["owners"] if x["userId"]==reviewer)
assert selected["acknowledged"]>=2 and selected["open"]>=1,selected
assert selected["overdue"]>=1,selected
ledger=sql(f"""SELECT action_type,affected_count,assignee_id FROM fba_purchase_exception_batch
 WHERE tenant_id='{tenant}' AND request_key='{key}'""")
assert ledger==f"ASSIGN|2|{reviewer}",ledger
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{market}'""")=="0"
print("PASS V79: PG18 Owner-only workload, active assignee, atomic 2-line assignment, idempotent replay, conflict/no partial audit, zero inventory writes")
