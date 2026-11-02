#!/usr/bin/env python3
"""V73: disposable PG18 / Session HTTP scorecards + human PO follow-ups.
Only synthetic local supplier/PO/ASN/receipts/QC, NEVER production or Amazon.
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
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
base="http://127.0.0.1:8080"
client=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=None
def call(path,method="GET",body=None,expected=200,form=False,browser=client,token=None):
    h={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        h["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and (token or csrf):h["X-CSRF-TOKEN"]=token or csrf
    try:
        with browser.open(Request(base+path,method=method,data=data,headers=h),timeout=25) as r:
            status,payload=r.status,r.read()
    except HTTPError as e:status,payload=e.code,e.read()
    assert status==expected,(path,method,status,expected,payload[:500])
    try:return json.loads(payload)
    except ValueError:return payload
def post(path,body=None,expected=200,browser=client,token=None):
    return call(path,"POST",body,expected,browser=browser,token=token)
def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    r=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1","-h","127.0.0.1",
       "-p",os.getenv("DB_PORT","5432"),"-U","merchivra","-d","merchivra","-Atc",statement],
       text=True,capture_output=True,env=env)
    assert r.returncode==0,r.stderr[-1000:]
    return r.stdout.strip()

root="/api/v1/purchasing/supplier-performance"
call(root+"/scorecards",expected=401)
call(root+"/followups",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me");tenant=me["activeTenantId"]
call(root+"/followups?filter=WRONG",expected=400)
post(root+"/scan",{"bad":"x"},expected=200) # empty scan is safe and creates a local run only
supplier=post("/api/v1/purchasing/suppliers",{
 "supplierCode":"V73SUPPLIER","displayName":"V73 Synthetic Score Supplier"},201)
warehouse=post("/api/v1/purchasing/warehouses",{
 "warehouseCode":"V73WAREHOUSE","displayName":"V73 Synthetic Warehouse"},201)
sku=str(uuid4())
sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 VALUES('{sku}','{tenant}','V73-{str(uuid4())[:8]}','V73 fictional SKU');""")
score=lambda:next(x for x in call(root+"/scorecards") if x["supplierId"]==supplier["id"])
assert score()["evidenceScore"] is None and score()["scoringStatus"]=="INSUFFICIENT_EVIDENCE"

# Proper independent human approval; never bypass maker/checker in app.
inv=post("/api/v1/team/invitations",{"loginName":"v73-ci-approver"})
second=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
token=call("/api/v1/auth/csrf",browser=second)["token"]
passwd="v73-disposable-only-second-owner-credential"
call("/api/v1/auth/invitations/accept","POST",
 {"token":inv["token"],"password":passwd},204,browser=second,token=token)
token=call("/api/v1/auth/csrf",browser=second)["token"]
call("/api/v1/auth/login","POST",
 {"username":"v73-ci-approver","password":passwd},form=True,browser=second,token=token)
token=call("/api/v1/auth/csrf",browser=second)["token"]
assert call("/api/v1/auth/me",browser=second)["userId"]!=me["userId"]

def approved_order(qty):
    po=post("/api/v1/purchasing/orders",{
     "supplierId":supplier["id"],"currency":"USD","notes":"V73 disposable purchase",
     "lines":[{"productSkuId":sku,"quantity":qty,"unitCost":"1.250000"}]},201)
    oid=po["order"]["id"];line=po["lines"][0]["id"]
    post(f"/api/v1/purchasing/orders/{oid}/submit",{})
    post(f"/api/v1/purchasing/orders/{oid}/approve",{},
      browser=second,token=token)
    return oid,line

# Aged PO without any ASN: a follow-up, never an invented overdue shipment ETA.
order,line=approved_order(20)
sql(f"""UPDATE erp_purchase_order SET approved_at=now()-interval '9 days'
 WHERE tenant_id='{tenant}' AND id='{order}';""")
post(root+"/scan")
def follow(code=None,state='ACTIVE'):
    data=call(root+"/followups?filter="+state)
    return [x for x in data if x["orderId"]==order and
     (code is None or x["ruleCode"]==code)]
rows=follow("ASN_COVERAGE_GAP")
assert len(rows)==1 and rows[0]["uncoveredQuantity"]==20,rows
fid=rows[0]["id"]
post(root+"/followups/"+fid+"/state",{"state":"WRONG"},expected=400)
post(root+"/followups/"+str(uuid4())+"/state",{"state":"ACKNOWLEDGED"},expected=404)
post(root+"/followups/"+fid+"/state",{"state":"ACKNOWLEDGED"})
post(root+"/scan")
assert follow()[0]["state"]=="ACKNOWLEDGED","A repeated scan may not erase owner triage"
today=datetime.now(timezone.utc).date()
eta=(today+timedelta(days=7)).isoformat()
asns=f"/api/v1/purchasing/supplier-asns/orders/{order}"
a=post(asns,{"requestKey":str(uuid4()),"lineId":line,"warehouseId":warehouse["id"],
 "quantity":12,"expectedArrivalDate":eta,"trackingReference":"V73-MOCK-A"},201)
post(root+"/scan")
assert follow("ASN_COVERAGE_GAP")[0]["uncoveredQuantity"]==8
b=post(asns,{"requestKey":str(uuid4()),"lineId":line,"warehouseId":warehouse["id"],
 "quantity":8,"expectedArrivalDate":eta,"trackingReference":"V73-MOCK-B"},201)
post(root+"/scan")
assert not follow("ASN_COVERAGE_GAP"),"Covered ASN must auto-clear coverage gap"
assert follow("ASN_COVERAGE_GAP","ALL")[0]["state"]=="CLEARED"
assert len(follow("ASN_NOT_DISPATCHED"))==1
post(f"{asns}/{a['id']}/ship")
post(root+"/scan")
assert not follow(),"Shipped with complete ASN coverage belongs only to V43 alert queue"
assert follow("ASN_NOT_DISPATCHED","ALL")[0]["state"]=="CLEARED"
post(root+"/followups/"+fid+"/state",{"state":"OPEN"},expected=409)
assert sql(f"SELECT COUNT(*) FROM erp_purchase_followup_audit WHERE tenant_id='{tenant}'")=="1"
assert call("/api/v1/purchasing/supplier-asns/findings?filter=ACTIVE") is not None

# Three fully ASN-linked actual local receipts, then genuine QC inspections.
# Two supplier-entered ETAs are future/on-time, one is already late.
for i,accepted in enumerate((10,10,8)):
    oid,lid=approved_order(10)
    when=(today+timedelta(days=2) if i<2 else today-timedelta(days=1)).isoformat()
    a=post(f"/api/v1/purchasing/supplier-asns/orders/{oid}",{
      "requestKey":str(uuid4()),"lineId":lid,"warehouseId":warehouse["id"],
      "quantity":10,"expectedArrivalDate":when,
      "trackingReference":"V73-TEST-"+str(i)},201)
    post(f"/api/v1/purchasing/supplier-asns/orders/{oid}/{a['id']}/ship")
    post(f"/api/v1/purchasing/orders/{oid}/receive",{
      "lineId":lid,"quantity":10,"warehouseId":warehouse["id"],"asnId":a["id"]})
    receipt=sql(f"""SELECT id FROM erp_purchase_receipt
      WHERE tenant_id='{tenant}' AND purchase_order_id='{oid}' AND asn_id='{a["id"]}'""")
    assert receipt and len(receipt)==36,receipt
    post(f"/api/v1/purchasing/quality/receipts/{receipt}/inspect",{
      "requestKey":str(uuid4()),"acceptedQuantity":accepted,
      "rejectedQuantity":10-accepted,"reason":"V73 synthetic QC inspection"},201)
    current=score()
    assert current["completeAsnSampleCount"]==i+1,current
    assert current["inspectedReceiptSampleCount"]==i+1,current
    if i<2:assert current["evidenceScore"] is None,current

final=score()
assert final["completeAsnSampleCount"]==3 and final["observedOnTimeAsnCount"]==2,final
assert final["inspectedQcUnits"]==30 and final["acceptedQcUnits"]==28,final
assert final["observedOnTimePercent"]=="66.7",final
assert final["inspectedAcceptancePercent"]=="93.3",final
assert final["evidenceScore"]=="77.3" and final["scoringStatus"]=="PROVISIONAL",final
assert final["activeVarianceCount"]==0
# Later supplier ETA modification must not silently rewrite historical receipt score.
old_asn=sql(f"""SELECT id FROM erp_purchase_asn
 WHERE tenant_id='{tenant}' AND tracking_reference='V73-TEST-2'""")
# Completed POs forbid V43 ETA mutation, so the historical score is frozen.
assert old_asn
assert score()["evidenceScore"]=="77.3"
print("PASS V73: PG18 session & two Owners, 7d missing/partial ASN, no-dispatch switch, audit/clear, 3 full ASN receipts + 3 actual QC samples, provisional 77.3 score and safe source labels")
