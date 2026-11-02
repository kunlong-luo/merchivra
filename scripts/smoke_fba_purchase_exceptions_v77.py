#!/usr/bin/env python3
"""V77 PostgreSQL 18 + actual HTTP follow-up to V76 fictional fixtures.
Run after smoke_fba_purchase_reconciliation_v76.py on an isolated CI database.
No live Seller/OAuth/FBA requests or inventory posting.
"""
import http.cookiejar
import json
import os
import subprocess
from datetime import datetime,timedelta,timezone
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,HTTPCookieProcessor,build_opener
from uuid import uuid4

assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
host="http://127.0.0.1:8080"
browser=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
def sql(text):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    result=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
      "-h","127.0.0.1","-p",os.getenv("DB_PORT","5432"),
      "-U","merchivra","-d","merchivra","-Atc",text],
      capture_output=True,text=True,env=env,check=False)
    assert result.returncode==0,result.stderr[-1000:]
    return result.stdout.strip()
csrf=None
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with browser.open(Request(host+path,headers=headers,data=data,method=method),
                          timeout=20) as response:
            status,payload=response.status,response.read()
    except HTTPError as err:
        status,payload=err.code,err.read()
    assert status==expected,(method,path,status,expected,payload[:400])
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
def post(path,body,expected=200):
    return call(path,"POST",body,expected)
foreign=str(uuid4())
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation/workbench",
     expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
tenant=call("/api/v1/auth/me")["activeTenantId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp, "Run V76 fixture first"
route=f"/api/v1/fba/marketplaces/{mp}/purchase-reconciliation/workbench"
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation/workbench",
     expected=404)
board=call(route)
assert board["amazonVerified"] is False and board["fbaStockCredited"] is False
assert board["policy"]["version"]==0 and board["shipmentScope"]>=5
assert len(board["items"])>=5 and not board["truncated"]
rows={r["shipmentReference"]:r for r in board["items"]}
pending=rows["V76-IDENTITY-PENDING"]
unlinked=rows["V76-UNLINKED-DRAFT"]
confirmed=rows["V76-IDENTITY-CONFIRMED"]
assert all(r["version"]==0 for r in [pending,unlinked,confirmed])
assert pending["findingCode"]=="PENDING_IDENTITY_REVIEW"
assert pending["assigneeId"] is None and pending["dueAt"] is None
assert all(len(r["fingerprint"])==64 for r in [pending,unlinked,confirmed])
reviewer=sql("""SELECT u.id FROM app_user u
 JOIN local_credential c ON c.user_id=u.id
 WHERE c.login_name='v76-ci-independent-owner'""")
assert reviewer and any(p["userId"]==reviewer for p in board["assignees"])
def act(row,action,note,**extra):
    return post(f"{route}/cases/{row['lineId']}/actions",{
        "action":action,"expectedVersion":row["version"],
        "expectedFingerprint":row["fingerprint"],"assigneeId":None,
        "dueAt":None,"note":note,**extra})
assigned=act(pending,"ASSIGN","V77 assigned to an independent Owner",
             assigneeId=reviewer)
assert assigned["state"]=="OPEN" and assigned["version"]==1
assert assigned["assigneeId"]==reviewer
assert assigned["dueAt"] is not None
acked=act(assigned,"ACKNOWLEDGE","V77 acknowledged pending identity review")
assert acked["state"]=="ACKNOWLEDGED" and acked["version"]==2
post(f"{route}/cases/{pending['lineId']}/actions",{
    "action":"RESOLVE","expectedVersion":acked["version"],
    "expectedFingerprint":acked["fingerprint"],
    "note":"V77 cannot close unverified independent identity"},409)

# Atomic batch with deterministic request identity, including safe retry.
key=str(uuid4())
selection=[{"lineId":x["lineId"],"expectedVersion":x["version"],
            "expectedFingerprint":x["fingerprint"]} for x in [unlinked,confirmed]]
payload={"requestKey":key,"lines":selection,
         "note":"V77 bulk local manual owner acknowledgement"}
one=post(route+"/bulk-acknowledge",payload)
assert one=={"requestKey":key,"acknowledgedCount":2}
assert post(route+"/bulk-acknowledge",payload)==one
post(route+"/bulk-acknowledge",{
    **payload,"note":"V77 a different conflicting manual request key payload"},409)
def rows_now():
    return {r["shipmentReference"]:r for r in call(route)["items"]}
new=rows_now()
assert new["V76-UNLINKED-DRAFT"]["version"]==1
assert new["V76-IDENTITY-CONFIRMED"]["version"]==1
audit_count=sql(f"""SELECT count(*) FROM fba_purchase_exception_audit a
 JOIN fba_purchase_exception_case c ON c.tenant_id=a.tenant_id
  AND c.id=a.case_id
 WHERE c.tenant_id='{tenant}' AND
 c.inbound_line_id IN ('{unlinked['lineId']}','{confirmed['lineId']}')""")
assert audit_count=="2",audit_count

# A stale version aborts the entire batch; no partial audit events.
bad_key=str(uuid4())
post(route+"/bulk-acknowledge",{
 "requestKey":bad_key,
 "lines":[{"lineId":x["lineId"],"expectedVersion":0,
           "expectedFingerprint":x["fingerprint"]} for x in [unlinked,confirmed]],
 "note":"V77 rejecting stale concurrent bulk attempts"},409)
assert sql(f"""SELECT count(*) FROM fba_purchase_exception_batch
 WHERE tenant_id='{tenant}' AND request_key='{bad_key}'""")=="0"
assert sql(f"""SELECT count(*) FROM fba_purchase_exception_audit a
 JOIN fba_purchase_exception_case c ON c.tenant_id=a.tenant_id
  AND c.id=a.case_id
 WHERE c.tenant_id='{tenant}' AND
 c.inbound_line_id IN ('{unlinked['lineId']}','{confirmed['lineId']}')""")=="2"

# Versioned reminder policy: in-app only, no email/scheduler.
policy=post(route+"/policy",{
 "enabled":True,"leadHours":2,"defaultDueHours":4,
 "expectedVersion":0,"note":"V77 explicit local due-time alert policy"})
assert policy["version"]==1 and policy["leadHours"]==2
post(route+"/policy",{
 "enabled":False,"leadHours":1,"defaultDueHours":4,
 "expectedVersion":0,"note":"V77 stale reminder policy must not overwrite"},409)
due=(datetime.now(timezone.utc)+timedelta(minutes=30)).isoformat()
adjusted=act(rows_now()["V76-UNLINKED-DRAFT"],"SET_DUE",
             "V77 owner revised due date to thirty minutes",dueAt=due)
assert adjusted["reminder"]=="DUE_SOON",adjusted
assert len(call(route+"/policy/history"))==1
assert len(call(f"{route}/cases/{unlinked['lineId']}/history"))==2

# V76 source evidence changes must invalidate previous human acknowledgement.
asn_id=sql(f"""SELECT asn_id FROM fba_inbound_purchase_link
 WHERE tenant_id='{tenant}' AND shipment_id='{confirmed['shipmentId']}'
 AND state='CONFIRMED'""")
assert asn_id
old=rows_now()["V76-IDENTITY-CONFIRMED"]
sql(f"""UPDATE erp_purchase_asn SET status='CANCELLED'
 WHERE tenant_id='{tenant}' AND id='{asn_id}'""")
drift=rows_now()["V76-IDENTITY-CONFIRMED"]
assert drift["stale"] and drift["reminder"]=="RECHECK",drift
assert drift["fingerprint"]!=old["fingerprint"]
post(f"{route}/cases/{confirmed['lineId']}/actions",{
 "action":"ACKNOWLEDGE","expectedVersion":old["version"],
 "expectedFingerprint":old["fingerprint"],
 "note":"V77 stale evidence cannot pass acknowledgement"},409)
updated=act(drift,"ACKNOWLEDGE","V77 acknowledged new supplier status evidence")
assert not updated["stale"] and updated["version"]==2
assert updated["state"]=="ACKNOWLEDGED"
closed=act(updated,"RESOLVE",
           "V77 reviewed supplier drift and documented local disposition")
assert closed["state"]=="RESOLVED" and closed["version"]==3
post(f"{route}/cases/{confirmed['lineId']}/actions",{
 "action":"ACKNOWLEDGE","expectedVersion":closed["version"],
 "expectedFingerprint":closed["fingerprint"],
 "note":"V77 resolved local case cannot silently reopen"},409)
reopened=act(closed,"REOPEN",
             "V77 explicitly reopened local work for further manual follow up")
assert reopened["state"]=="OPEN" and reopened["version"]==4
assert len(call(f"{route}/cases/{confirmed['lineId']}/history"))==5
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="0"
print("PASS V77: PG18 Owner Session/CSRF, active-assignee, audit, atomic bulk/idempotency, reminder CAS, due-soon, drift recheck and zero Amazon/FBA writes")
