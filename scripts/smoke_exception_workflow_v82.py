#!/usr/bin/env python3
"""V82 PostgreSQL 18 disposable fictional data, after V76-V81 smoke.
Audited priority policies + human-confirmed atomic close. Never run on real Seller data.
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
    p=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
     "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",statement],
     capture_output=True,text=True,env=env,check=False)
    assert p.returncode==0,p.stderr[-1200:]
    return p.stdout.strip()
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method=="POST" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,data=data,
                              headers=headers,method=method),timeout=30) as r:
            status,payload=r.status,r.read()
    except HTTPError as e:
        status,payload=e.code,e.read()
    assert status==expected,(method,path,status,expected,payload[:750])
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
def post(path,body,expected=200):return call(path,"POST",body,expected)
foreign=str(uuid4())
base="/api/v1/exception-insights/marketplaces/"
call(base+foreign+"/priority-rules",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
tenant=call("/api/v1/auth/me")["activeTenantId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp
policy=base+mp+"/priority-rules"
cases=f"/api/v1/fba/marketplaces/{mp}/purchase-reconciliation/workbench"
call(base+foreign+"/priority-rules",expected=404)
call(base+foreign+"/priority-rules/audit",expected=404)
rules=call(policy)
assert len(rules)==9 and all(x["version"]==0 and not x["customized"] for x in rules)
assert next(x for x in rules if x["reasonCode"]=="PAST_DUE")["priority"]=="P0"
post(policy+"/PAST_DUE",{
 "priority":"P1","expectedVersion":0,
 "note":"V82 must reject downgrading a known overdue case"},400)
post(policy+"/INDEPENDENT_IDENTITY_REVIEW",{
 "priority":"P2","expectedVersion":0,
 "note":"V82 second Owner review may not be downgraded"},400)
saved=post(policy+"/NEEDS_OWNER",{
 "priority":"P1","expectedVersion":0,
 "note":"V82 local operator escalates unassigned triage"})
assert saved["priority"]=="P1" and saved["version"]==1 and saved["customized"]
post(policy+"/NEEDS_OWNER",{
 "priority":"P0","expectedVersion":0,
 "note":"V82 concurrent update must not be overwritten"},409)
saved_again=post(policy+"/NEEDS_OWNER",{
 "priority":"P0","expectedVersion":1,
 "note":"V82 local Owner raises unassigned source priority"})
assert saved_again["priority"]=="P0" and saved_again["version"]==2
assert len(call(policy+"/audit"))==2
assert sql(f"""SELECT count(*) FROM fba_exception_priority_audit
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="2"
assert next(x for x in call(policy) if x["reasonCode"]=="NEEDS_OWNER")["priority"]=="P0"
# The read-only recommendations stay grounded in original reason and cached rules.
insights=call(base+mp)
assert insights["amazonVerified"] is False and insights["fbaStockCredited"] is False
for row in insights["recommendations"]:
    if row["reasonCode"]=="NEEDS_OWNER":
        assert row["priority"]=="P0",row
# Explicit confirmation is mandatory server-side; signed-in Session/CSRF alone
# is not sufficient to mutate the human workflow.
board={x["shipmentReference"]:x for x in call(cases)["items"]}
good=board["V76-UNLINKED-DRAFT"]
blocked=board["V76-IDENTITY-PENDING"]
assert good["state"]=="ACKNOWLEDGED" and good["assigneeId"] is not None and not good["stale"]
select=lambda x:{"lineId":x["lineId"],"expectedVersion":x["version"],
                 "expectedFingerprint":x["fingerprint"]}
payload={"requestKey":str(uuid4()),"confirmed":False,
 "lines":[select(good)],"note":"V82 human evidence checked for local completion"}
post(cases+"/bulk-resolve",payload,400)
assert sql(f"""SELECT count(*) FROM fba_purchase_exception_batch
 WHERE tenant_id='{tenant}' AND request_key='{payload['requestKey']}'""")=="0"
# At least one blocked line forces entire batch rollback, with no partial audit.
audit_before=int(sql(f"""SELECT count(*) FROM fba_purchase_exception_audit
 WHERE tenant_id='{tenant}'"""))
invalid={**payload,"requestKey":str(uuid4()),"confirmed":True,
 "lines":[select(good),select(blocked)]}
post(cases+"/bulk-resolve",invalid,409)
assert sql(f"""SELECT count(*) FROM fba_purchase_exception_audit
 WHERE tenant_id='{tenant}'""")==str(audit_before)
assert sql(f"""SELECT count(*) FROM fba_purchase_exception_batch
 WHERE tenant_id='{tenant}' AND request_key='{invalid['requestKey']}'""")=="0"
key=str(uuid4())
confirmed={**payload,"requestKey":key,"confirmed":True}
receipt=post(cases+"/bulk-resolve",confirmed)
assert receipt=={"requestKey":key,"resolvedCount":1},receipt
assert post(cases+"/bulk-resolve",confirmed)==receipt
post(cases+"/bulk-resolve",{**confirmed,"note":"V82 conflicting retry must be rejected"},409)
post(cases+"/bulk-resolve",{**confirmed,"requestKey":str(uuid4())},409)
after={x["shipmentReference"]:x for x in call(cases)["items"]}
assert after["V76-UNLINKED-DRAFT"]["state"]=="RESOLVED"
assert after["V76-UNLINKED-DRAFT"]["version"]==good["version"]+1
assert int(sql(f"""SELECT count(*) FROM fba_purchase_exception_audit
 WHERE tenant_id='{tenant}'"""))==audit_before+1
assert sql(f"""SELECT action_type,affected_count FROM fba_purchase_exception_batch
 WHERE tenant_id='{tenant}' AND request_key='{key}'""")=="RESOLVE|1"
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="0"
print("PASS V82: PG18 immutable priority risk floors, CAS/audit, explicit confirmation, rollback, idempotent RESOLVE and zero FBA stock writes")
