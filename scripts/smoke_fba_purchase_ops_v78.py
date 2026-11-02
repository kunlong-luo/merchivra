#!/usr/bin/env python3
"""V78: PG18 real HTTP/CSRF, V77 persisted case metrics and existing Ops inbox.
Run AFTER V76 and V77 fictional fixture tests. Amazon outbounds are disabled.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,HTTPCookieProcessor,build_opener

assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
base="http://127.0.0.1:8080"
csrf=""
def sql(query):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    result=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
      "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
      capture_output=True,text=True,env=env,check=False)
    assert result.returncode==0,result.stderr[-800:]
    return result.stdout.strip()
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method=="POST" and csrf: headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request(base+path,method=method,data=data,headers=headers),timeout=30) as response:
            status,payload=response.status,response.read()
    except HTTPError as err:
        status,payload=err.code,err.read()
    assert status==expected,(method,path,status,expected,payload[:350])
    return json.loads(payload)
def post(path,body=None,expected=200):
    return call(path,"POST",body,expected)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
  "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
  "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
tenant=call("/api/v1/auth/me")["activeTenantId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp, "Run V76/V77 fixtures first"
work=f"/api/v1/fba/marketplaces/{mp}/purchase-reconciliation/workbench"
board=call(work)
rows={r["shipmentReference"]:r for r in board["items"]}
confirmed=rows["V76-IDENTITY-CONFIRMED"]
unlinked=rows["V76-UNLINKED-DRAFT"]
assert not confirmed["stale"] and unlinked["state"]=="ACKNOWLEDGED"
metrics=call(work+"/metrics")
assert metrics["total"]>=3 and metrics["acknowledged"]>=2,metrics
assert metrics["resolved"]>=0 and metrics["meanResolutionHours"] is None,metrics
assert metrics["overdue"]>=0
# A synthetic overdue deadline and a new ASN observation cause separate alerts.
sql(f"""UPDATE fba_purchase_exception_case
 SET due_at=now()-interval '90 minutes'
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'
 AND inbound_line_id='{unlinked['lineId']}'""")
asn=sql(f"""SELECT asn_id FROM fba_inbound_purchase_link
 WHERE tenant_id='{tenant}' AND shipment_id='{confirmed['shipmentId']}'
 AND state='CONFIRMED'""")
assert asn
sql(f"""UPDATE erp_purchase_asn SET status='SHIPPED'
 WHERE tenant_id='{tenant}' AND id='{asn}'""")
assert next(x for x in call(work)["items"] if x["lineId"]==confirmed["lineId"])["stale"]
metrics=call(work+"/metrics")
assert metrics["overdue"]>=1
rules="/api/v1/operations/marketplaces/"+mp+"/rules"
configured={r["ruleCode"]:r for r in call(rules)}
assert configured["FBA_PO_EVIDENCE_CHANGED"]["enabled"]
assert configured["FBA_PO_EXCEPTION_OVERDUE"]["enabled"]
assert len(configured)==8
queue="/api/v1/operations"
post(queue+"/scan")
def current():
    return call(queue+"/findings?filter=ALL")
def pick(code,line):
    return [r for r in current() if r["marketplaceId"]==mp
            and r["ruleCode"]==code and r["sourceKey"]==line]
changed=pick("FBA_PO_EVIDENCE_CHANGED",confirmed["lineId"])
overdue=pick("FBA_PO_EXCEPTION_OVERDUE",unlinked["lineId"])
assert len(changed)==1 and changed[0]["state"]=="OPEN",changed
assert len(overdue)==1 and overdue[0]["state"]=="OPEN",overdue
assert changed[0]["dataOrigin"]=="MOCK" and overdue[0]["dataOrigin"]=="MOCK"
post(queue+"/scan")
assert pick("FBA_PO_EVIDENCE_CHANGED",confirmed["lineId"])[0]["id"]==changed[0]["id"]
# Reuse the existing versioned rule endpoint + audit, no second alert policy DB.
post(rules+"/FBA_PO_EXCEPTION_OVERDUE",{
  "enabled":False,"expectedVersion":0})
post(queue+"/scan")
assert pick("FBA_PO_EXCEPTION_OVERDUE",unlinked["lineId"])[0]["state"]=="CLEARED"
assert pick("FBA_PO_EVIDENCE_CHANGED",confirmed["lineId"])[0]["state"]=="OPEN"
history=call(rules+"/audit")
assert any(x["ruleCode"]=="FBA_PO_EXCEPTION_OVERDUE" and not x["newEnabled"]
           for x in history)
# Clearing the evidence change clears the queued recheck on the next scan.
sql(f"""UPDATE erp_purchase_asn SET status='CANCELLED'
 WHERE tenant_id='{tenant}' AND id='{asn}'""")
assert not next(x for x in call(work)["items"] if x["lineId"]==confirmed["lineId"])["stale"]
post(queue+"/scan")
assert pick("FBA_PO_EVIDENCE_CHANGED",confirmed["lineId"])[0]["state"]=="CLEARED"
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="0"
print("PASS V78: PG18 scoped SLA, evidence drift, overdue, dedup, tenant Ops queue, rule CAS/audit, clearing and zero Amazon writes")
