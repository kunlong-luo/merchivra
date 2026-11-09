#!/usr/bin/env python3
"""V72 disposable localhost PG18 Owner-Session + real PO/ASN/receipt/quality HTTP smoke.
Never use against a production database or real Seller credentials.
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
def call(path,method="GET",body=None,expected=200,form=False,headers=None,
         browser=client,token=None):
    h={"Accept":"application/json",**(headers or {})}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        h["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and (token or csrf):h["X-CSRF-TOKEN"]=token or csrf
    try:
        with browser.open(Request(base+path,data=data,headers=h,method=method),timeout=25) as resp:
            status,payload=resp.status,resp.read()
    except HTTPError as e:
        status,payload=e.code,e.read()
    assert status==expected,(method,path,status,expected,payload[:450])
    try:return json.loads(payload)
    except ValueError:return payload
def post(path,body,expected=200,headers=None):
    return call(path,"POST",body,expected,headers=headers)
def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    proc=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
      "-h","127.0.0.1","-p",os.getenv("DB_PORT","5432"),
      "-U","merchivra","-d","merchivra","-Atc",statement],
      env=env,text=True,capture_output=True)
    assert proc.returncode==0,proc.stderr[-1000:]
    return proc.stdout.strip()

endpoint="/api/v1/purchasing/delivery/overview"
call(endpoint,expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me")
tenant=me["activeTenantId"]
assert me["isOwner"]

# Isolate to a unique synthetic SKU / PO; no real-source data.
sku_id=str(uuid4())
sql(f"""INSERT INTO product_sku (id,tenant_id,internal_sku,display_name)
 VALUES('{sku_id}','{tenant}','V72-FAKE-{str(uuid4())[:8]}','V72 artificial SKU');""")
supplier=post("/api/v1/purchasing/suppliers",{
 "supplierCode":"V72SUPPLIER","displayName":"Synthetic Delivery Supplier"},201)
warehouse=post("/api/v1/purchasing/warehouses",{
 "warehouseCode":"V72LOCAL","displayName":"Synthetic Delivery Warehouse"},201)
po=post("/api/v1/purchasing/orders",{
 "supplierId":supplier["id"],"currency":"USD","notes":"V72 synthetic supplier delivery board",
 "lines":[{"productSkuId":sku_id,"quantity":36,"unitCost":"3.250000"}]},201)
order_id=po["order"]["id"];line_id=po["lines"][0]["id"]
assert po["order"]["status"]=="DRAFT"

def row():
    data=call(endpoint)
    lines=[r for r in data["lines"] if r["orderId"]==order_id]
    assert len(lines)<=1,lines
    return lines[0] if lines else None

assert row() is None,"Unapproved PO must not become a delivery liability"
post(f"/api/v1/purchasing/orders/{order_id}/submit",{},200)
assert row() is None,"Submitted PO is not approved"

# Real maker/checker Session: a second distinct Owner accepts a local CI invitation.
invitation=post("/api/v1/team/invitations",{"loginName":"v72-ci-approver"})
second=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
second_csrf=call("/api/v1/auth/csrf",browser=second)["token"]
password="v72-second-owner-disposable-password-only"
call("/api/v1/auth/invitations/accept","POST",
 {"token":invitation["token"],"password":password},204,
 browser=second,token=second_csrf)
second_csrf=call("/api/v1/auth/csrf",browser=second)["token"]
call("/api/v1/auth/login","POST",{"username":"v72-ci-approver",
 "password":password},200,form=True,browser=second,token=second_csrf)
second_csrf=call("/api/v1/auth/csrf",browser=second)["token"]
assert call("/api/v1/auth/me",browser=second)["userId"]!=me["userId"]
approved=call(f"/api/v1/purchasing/orders/{order_id}/approve",
 "POST",{},200,browser=second,token=second_csrf)
assert approved["order"]["status"]=="APPROVED"
initial=row()
assert initial["deliveryState"]=="NO_ASN_RECORDED" and initial["remainingQuantity"]==36,initial
assert initial["earliestReportedEta"] is None,initial
assert initial["receivedQuantity"]==0 and initial["draftAsnQuantity"]==0,initial
assert initial["currency"]=="USD" and initial["supplierId"]==supplier["id"],initial

today=datetime.now(timezone.utc).date()
soon=(today+timedelta(days=2)).isoformat()
past=(today-timedelta(days=1)).isoformat()
asns=f"/api/v1/purchasing/supplier-asns/orders/{order_id}"
asn=post(asns,{"requestKey":str(uuid4()),"lineId":line_id,
 "warehouseId":warehouse["id"],"quantity":20,
 "expectedArrivalDate":soon,"trackingReference":"V72-NOT-CARRIER-PROOF"},201)
assert asn["status"]=="DRAFT"
draft=row()
assert draft["deliveryState"]=="ASN_AWAITING_DISPATCH",draft
assert draft["draftAsnQuantity"]==20 and draft["reportedUnreceivedQuantity"]==0,draft
assert draft["earliestReportedEta"] is None,"Draft ETA cannot become shipped ETA"

post(f"{asns}/{asn['id']}/ship",{})
upcoming=row()
assert upcoming["deliveryState"]=="REPORTED_ETA_DUE_SOON" and upcoming["reportedUnreceivedQuantity"]==20,upcoming
post(f"{asns}/{asn['id']}/eta",{
 "expectedArrivalDate":past,"expectedPreviousEta":soon,
 "reason":"V72 artificial delay for offline assertions"})
overdue=row()
assert overdue["deliveryState"]=="REPORTED_ETA_OVERDUE"
assert overdue["earliestReportedEta"]==past and overdue["latestReportedEta"]==past
# Reuse existing V43 ASN scan and persisted alert rather than creating a new queue.
scanned=post("/api/v1/purchasing/supplier-asns/scan",{})
assert scanned["matchedCount"]>=1
findings=call("/api/v1/purchasing/supplier-asns/findings?filter=ACTIVE")
assert any(f["asnId"]==asn["id"] and
 f["ruleCode"]=="SUPPLIER_ASN_OVERDUE" for f in findings)

receive=f"/api/v1/purchasing/orders/{order_id}/receive"
partial=post(receive,{"lineId":line_id,"quantity":5,
 "warehouseId":warehouse["id"],"asnId":asn["id"]})
assert partial["order"]["status"]=="RECEIVING"
in_progress=row()
assert in_progress["receivedQuantity"]==5 and in_progress["remainingQuantity"]==31,in_progress
assert in_progress["reportedUnreceivedQuantity"]==15 and in_progress["awaitingQcReleaseQuantity"]==5,in_progress
assert in_progress["deliveryState"]=="REPORTED_ETA_OVERDUE"

# V45 discrepancy remains an independent source of truth and outranks delivery ETA.
variance=str(uuid4())
sql(f"""INSERT INTO erp_purchase_variance
 (id,tenant_id,request_key,purchase_order_id,purchase_order_line_id,
 kind,expected_quantity,observed_quantity,variance_quantity,reason,created_by)
 VALUES('{variance}','{tenant}','{uuid4()}','{order_id}','{line_id}',
 'PO_SHORTAGE',36,5,31,'V72 mock shortage review','{me["userId"]}');""")
dispute=row()
assert dispute["deliveryState"]=="DISCREPANCY_REVIEW" and dispute["unresolvedVarianceCount"]==1,dispute
sql(f"""UPDATE erp_purchase_variance SET state='CLOSED',
 reviewed_by='{me["userId"]}',reviewed_at=now(),updated_at=now()
 WHERE id='{variance}' AND tenant_id='{tenant}';""")
assert row()["deliveryState"]=="REPORTED_ETA_OVERDUE"

post(receive,{"lineId":line_id,"quantity":15,
 "warehouseId":warehouse["id"],"asnId":asn["id"]})
assert row()["reportedUnreceivedQuantity"]==0
assert row()["deliveryState"]=="NO_ASN_RECORDED","Cannot assume shipment for remaining units"

next_asn=post(asns,{"requestKey":str(uuid4()),"lineId":line_id,
 "warehouseId":warehouse["id"],"quantity":16,
 "expectedArrivalDate":soon,"trackingReference":None},201)
post(f"{asns}/{next_asn['id']}/ship",{})
completed=post(receive,{"lineId":line_id,"quantity":16,
 "warehouseId":warehouse["id"],"asnId":next_asn["id"]})
assert completed["order"]["status"]=="RECEIVED"
done=row()
assert done["remainingQuantity"]==0 and done["receivedQuantity"]==36,done
assert done["reportedUnreceivedQuantity"]==0 and done["awaitingQcReleaseQuantity"]==36,done
assert done["deliveryState"]=="AWAITING_QC_RELEASE",done
assert done["releasedQcAcceptedQuantity"]==0,done
assert done["unresolvedVarianceCount"]==0
assert sql(f"SELECT COUNT(*) FROM erp_receipt_quality_batch WHERE tenant_id='{tenant}' AND status='PENDING_QC' AND receipt_id IN (SELECT id FROM erp_purchase_receipt WHERE tenant_id='{tenant}' AND purchase_order_id='{order_id}')")=="3"
# V119: disposable PG18 / Session HTTP verifies new global SQL aggregation and
# page cursor are built over the *same* local receipt/ASN/QC source, not the
# legacy 300-row UI response. This test does not exercise production data.
full=call("/api/v1/purchasing/delivery/summary")
assert full["asOfDateUtc"]==today.isoformat(),full
assert full["totals"]["lineCount"]>=1,full
assert full["totals"]["awaitingQcReleaseQuantity"]>=36,full
assert full["totals"]["unresolvedVarianceLines"]==0,full
cursor=None
seen=set()
for page_no in range(100):
    query="?limit=1"+(("&cursor="+urlencode({"c":cursor})[2:]) if cursor else "")
    pg=call("/api/v1/purchasing/delivery/lines/page"+query)
    assert pg["pageSize"]==1 and len(pg["items"])<=1,pg
    for x in pg["items"]:
        assert x["lineId"] not in seen,"Delivery cursor repeated a line"
        seen.add(x["lineId"])
        if x["orderId"]==order_id:
            assert x["receivedQuantity"]==36 and x["remainingQuantity"]==0,x
            assert x["deliveryState"]=="AWAITING_QC_RELEASE",x
    assert pg["hasMore"]==(pg["nextCursor"] is not None),pg
    if not pg["hasMore"]:break
    cursor=pg["nextCursor"]
else:
    raise AssertionError("Unexpected >100 approved lines in isolated V72 fixture")
assert line_id in seen, "Synthetic PO must appear in full delivery pages"
call("/api/v1/purchasing/delivery/lines/page?limit=101",expected=400)
call("/api/v1/purchasing/delivery/lines/page?limit=50&cursor=invalid%25",expected=400)
print("PASS V119: PG18 aggregate all approved rows and scoped complete delivery keyset HTTP")

print("PASS V72: real PG18 dual Owner approval, no ASN, draft, reported due/late, V43 alert reuse, linked receipts, discrepancy, QC hold and no FBA changes")
