#!/usr/bin/env python3
"""V84 isolated PG18 Owner-scoped permission + real batch-audit lineage regression.
Must run after V76-V83 disposable fixtures. No Seller API / inventory writes.
"""
import http.cookiejar,json,os,subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,HTTPCookieProcessor,build_opener
from uuid import uuid4
assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
def sql(stmt):
 env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
 p=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
   "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",stmt],
   capture_output=True,text=True,env=env,check=False)
 assert p.returncode==0,p.stderr[-1000:]
 return p.stdout.strip()
def browser():return build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
admin=browser();staff=browser()
csrf={}
def call(path,method="GET",body=None,expected=200,client=admin,form=False):
 headers={"Accept":"application/json"}
 data=None
 if body is not None:
  data=(urlencode(body) if form else json.dumps(body)).encode()
  headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
 if method=="POST" and csrf.get(id(client)):headers["X-CSRF-TOKEN"]=csrf[id(client)]
 try:
  with client.open(Request("http://127.0.0.1:8080"+path,
   method=method,headers=headers,data=data),timeout=30) as r:status,payload=r.status,r.read()
 except HTTPError as err:status,payload=err.code,err.read()
 assert status==expected,(method,path,status,expected,payload[:900])
 try:return json.loads(payload)
 except (ValueError,TypeError):return payload
def login(client,name,password):
 csrf[id(client)]=call("/api/v1/auth/csrf",client=client)["token"]
 call("/api/v1/auth/login","POST",{"username":name,"password":password},
      client=client,form=True)
 csrf[id(client)]=call("/api/v1/auth/csrf",client=client)["token"]
 return call("/api/v1/auth/me",client=client)
foreign=str(uuid4())
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation/permissions/me",expected=401)
owner=login(admin,os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"])
other=login(staff,"v76-ci-independent-owner",
 "v76-ci-independent-owner-disposable-password")
tenant=owner["activeTenantId"]
assert tenant==other["activeTenantId"] and owner["userId"]!=other["userId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp
p=f"/api/v1/fba/marketplaces/{mp}/purchase-reconciliation/permissions"
w=f"/api/v1/fba/marketplaces/{mp}/purchase-reconciliation/workbench"
read=f"/api/v1/exception-insights/marketplaces/{mp}"
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation/permissions/me",expected=404)
own=call(p+"/me")
assert own["ownerId"]==owner["userId"] and all(own["grants"].values())
previous=call(w+"/batches")
assert previous and previous[0]["requestKey"]
# These V77-V83 fixture operations happen AFTER Flyway V78, so they are
# correctly linked already. Construct one old-format ledger entry without a
# batch_id-linked audit to explicitly exercise legacy compatibility.
recent=call(w+"/batches/"+previous[0]["requestKey"])
assert recent["lineageComplete"] and recent["linkedCount"]==recent["batch"]["affectedCount"]
legacy_key=str(uuid4())
sql(f"""INSERT INTO fba_purchase_exception_batch(
 id,tenant_id,seller_marketplace_id,request_key,payload_hash,
 actor_user_id,affected_count)
 VALUES('{uuid4()}','{tenant}','{mp}','{legacy_key}',
 '{'0'*64}','{owner['userId']}',1)""")
legacy=call(w+"/batches/"+legacy_key)
assert not legacy["lineageComplete"] and legacy["linkedCount"]==0
assert legacy["linkedActions"]==[]
call(w+"/batches/"+str(uuid4()),expected=404)
roster=call(p+"/owners")
assert len(roster)>=2 and all(not x["customized"] for x in roster)
assert call(p+"/me",client=staff)["grants"]["manage"] is True
restricted={"view":True,"assign":False,"review":False,"resolve":False,
            "configure":False,"manage":False}
change={"grants":restricted,"expectedVersion":0,
        "reason":"V84 scoped Owner human workflow permissions test"}
call(p+"/owners/"+owner["userId"],"POST",change,403)
call(p+"/owners/"+other["userId"],"POST",{
 **change,"grants":{**restricted,"manage":True}},400)
saved=call(p+"/owners/"+other["userId"],"POST",change)
assert saved["version"]==1 and saved["grants"]==restricted
call(p+"/owners/"+other["userId"],"POST",change,409)
assert call(p+"/me",client=staff)["grants"]==restricted
assert call(w,client=staff)["items"]
call(w+"/batches",client=staff)
call(read,client=staff)
call(read+"/priority-rules",client=staff)
call(w+"/bulk-resolve/preflight","POST",{"lines":[]},403,client=staff)
call(w+"/bulk-assign","POST",{},403,client=staff)
call(w+"/bulk-acknowledge","POST",{},403,client=staff)
call(w+"/policy","POST",{},403,client=staff)
call(read+"/priority-rules/NEEDS_OWNER","POST",{},403,client=staff)
call(p+"/owners",expected=403,client=staff)
assert len(call(p+"/audit"))==1
# Enable only REVIEW, leave assignment, completion and config denied. Existing
# Owner alone is not sufficient to bypass this local-workflow capability.
review_grants={**restricted,"review":True}
call(p+"/owners/"+other["userId"],"POST",{
 "grants":review_grants,"expectedVersion":1,
 "reason":"V84 allow reviewer to explicitly acknowledge only"})
rows={x["shipmentReference"]:x for x in call(w,client=staff)["items"]}
pending=rows["V76-IDENTITY-PENDING"]
payload={"requestKey":str(uuid4()),"lines":[{
 "lineId":pending["lineId"],"expectedVersion":pending["version"],
 "expectedFingerprint":pending["fingerprint"]}],
 "note":"V84 evidence acknowledged by limited reviewer, not resolved"}
before=sql(f"SELECT count(*) FROM fba_purchase_exception_audit WHERE tenant_id='{tenant}'")
receipt=call(w+"/bulk-acknowledge","POST",payload,client=staff)
assert receipt["acknowledgedCount"]==1
assert call(w+"/bulk-acknowledge","POST",payload,client=staff)==receipt
linked=call(w+"/batches/"+payload["requestKey"],client=staff)
assert linked["lineageComplete"] and linked["linkedCount"]==1
assert linked["batch"]["actionType"]=="ACKNOWLEDGE"
action=linked["linkedActions"][0]
assert action["lineId"]==pending["lineId"] and action["actorId"]==other["userId"]
assert action["action"]=="ACKNOWLEDGE" and action["newState"]=="ACKNOWLEDGED"
assert sql(f"""SELECT count(*) FROM fba_purchase_exception_audit a
 JOIN fba_purchase_exception_batch b ON b.tenant_id=a.tenant_id AND b.id=a.batch_id
 WHERE a.tenant_id='{tenant}' AND b.request_key='{payload['requestKey']}'""")=="1"
assert int(sql(f"SELECT count(*) FROM fba_purchase_exception_audit WHERE tenant_id='{tenant}'"))==int(before)+1
call(w+"/bulk-resolve/preflight","POST",{"lines":payload["lines"]},403,client=staff)
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="0"
print("PASS V84: Owner-only capability CAS/audit/deny gates, default compatibility, legacy honest lineage, exact new batch-to-case audit FK, replay and zero FBA writes")
