#!/usr/bin/env python3
"""V76: real localhost PG18/HTTP read-only FBA/PO/ASN reconciliation.
Uses fictional evidence only; no Amazon auth, shipment/finance writes or carrier claims.
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
host="http://127.0.0.1:8080"
owner=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=None
def call(path,method="GET",body=None,expected=200,form=False,
         browser=owner,token=None,headers=None):
    h={"Accept":"application/json",**(headers or {})}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        h["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and (token or csrf):h["X-CSRF-TOKEN"]=token or csrf
    try:
        with browser.open(Request(host+path,data=data,headers=h,method=method),timeout=25) as r:
            status,payload=r.status,r.read()
    except HTTPError as e:status,payload=e.code,e.read()
    assert status==expected,(method,path,status,expected,payload[:650])
    try:return json.loads(payload)
    except ValueError:return payload
def post(path,body=None,expected=200,browser=owner,token=None,headers=None):
    return call(path,"POST",body if body is not None else {},expected,
                browser=browser,token=token,headers=headers)
def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    result=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
      "-h","127.0.0.1","-p",os.getenv("DB_PORT","5432"),"-U","merchivra",
      "-d","merchivra","-Atc",statement],
      text=True,capture_output=True,env=env)
    assert result.returncode==0,result.stderr[-1300:]
    return result.stdout.strip()
foreign=str(uuid4())
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
session=call("/api/v1/auth/me")
tenant=session["activeTenantId"]
assert session["isOwner"]
call(f"/api/v1/fba/marketplaces/{foreign}/purchase-reconciliation",expected=404)

account=post("/api/v1/seller-accounts",{"displayName":"V76 Synthetic","region":"NA"},
 201,headers={"Idempotency-Key":"v76-reconcile-disposable-account"})["id"]
mp=post(f"/api/v1/seller-accounts/{account}/marketplaces",
 {"marketplaceId":"ATVPDKIKX0DER"},201)["id"]
route=f"/api/v1/fba/marketplaces/{mp}/purchase-reconciliation"
fba=f"/api/v1/fba/marketplaces/{mp}/shipments"
empty=call(route)
assert empty["lines"]==[] and empty["totals"]["examinedLines"]==0,empty
assert empty["amazonVerified"] is False and empty["fbaStockCredited"] is False

sku="V76-FICTIONAL-SKU"
product,listing,mapping=[str(uuid4()) for _ in range(3)]
sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 VALUES('{product}','{tenant}','V76-PRODUCT','V76 synthetic SKU');
 INSERT INTO marketplace_listing(id,tenant_id,seller_marketplace_id,seller_sku,
 mapping_status,data_origin)
 VALUES('{listing}','{tenant}','{mp}','{sku}','ACTIVE','MOCK');
 INSERT INTO listing_sku_mapping(id,tenant_id,listing_id,product_sku_id,
 status,valid_from,mapping_version)
 VALUES('{mapping}','{tenant}','{listing}','{product}','ACTIVE',now(),1);""")
supplier=post("/api/v1/purchasing/suppliers",{
 "supplierCode":"V76SUP","displayName":"V76 synthetic supplier"},201)["id"]
warehouse=post("/api/v1/purchasing/warehouses",{
 "warehouseCode":"V76LOCAL","displayName":"V76 synthetic local warehouse"},201)["id"]
po=post("/api/v1/purchasing/orders",{
 "supplierId":supplier,"currency":"USD","notes":"V76 local reconciliation-only",
 "lines":[{"productSkuId":product,"quantity":25,"unitCost":"2.000000"}]},201)
order=po["order"]["id"];poLine=po["lines"][0]["id"]
post(f"/api/v1/purchasing/orders/{order}/submit",{})
invite=post("/api/v1/team/invitations",{"loginName":"v76-ci-independent-owner"})
reviewer=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
token=call("/api/v1/auth/csrf",browser=reviewer)["token"]
password="v76-ci-independent-owner-disposable-password"
call("/api/v1/auth/invitations/accept","POST",
 {"token":invite["token"],"password":password},204,browser=reviewer,token=token)
token=call("/api/v1/auth/csrf",browser=reviewer)["token"]
call("/api/v1/auth/login","POST",
 {"username":"v76-ci-independent-owner","password":password},form=True,
 browser=reviewer,token=token)
token=call("/api/v1/auth/csrf",browser=reviewer)["token"]
assert call("/api/v1/auth/me",browser=reviewer)["userId"]!=session["userId"]
post(f"/api/v1/purchasing/orders/{order}/approve",{},browser=reviewer,token=token)
eta=(datetime.now(timezone.utc).date()+timedelta(days=7)).isoformat()
asn=post(f"/api/v1/purchasing/supplier-asns/orders/{order}",{
 "requestKey":str(uuid4()),"lineId":poLine,"warehouseId":warehouse,
 "quantity":18,"expectedArrivalDate":eta,"trackingReference":"V76-NOT-CARRIER-VERIFIED"},201)
asnId=asn["id"]
post(f"/api/v1/purchasing/supplier-asns/orders/{order}/{asnId}/ship",{})
def shipment(name,quantity):
    data=post(fba,{"requestKey":str(uuid4()),"reference":name,
     "origin":"MOCK","lines":[{"sellerSku":sku,"quantity":quantity}]})
    return data["shipment"]["id"],data["lines"][0]["id"]
def by_id():
    report=call(route)
    assert report["amazonVerified"] is False
    assert report["fbaStockCredited"] is False
    return report,{x["shipmentId"]:x for x in report["lines"]}
def proof(shipmentId,lineId,proofRef):
    return post(f"{fba}/{shipmentId}/purchase-links",{
     "requestKey":str(uuid4()),"inboundLineId":lineId,
     "purchaseOrderLineId":poLine,"asnId":asnId,"proofReference":proofRef})
confirmed,line1=shipment("V76-IDENTITY-CONFIRMED",10)
pending,line2=shipment("V76-IDENTITY-PENDING",5)
unlinked,line3=shipment("V76-UNLINKED-DRAFT",3)
reported,line4=shipment("V76-UNLINKED-REPORTED",4)
over,line5=shipment("V76-LOCAL-OVER-REPORT",2)
first=proof(confirmed,line1,"V76 first explicit paper identity proof")
second=proof(pending,line2,"V76 second pending identity proof")
post(f"{fba}/{confirmed}/purchase-links/{first['id']}/confirm",
 {"reason":"V76 second Owner checked explicit source IDs"},
 browser=reviewer,token=token)
post(f"{fba}/{confirmed}/dispatch",{})
post(f"{fba}/{pending}/dispatch",{},expected=409)
post(f"{fba}/{reported}/dispatch",{})
post(f"{fba}/{reported}/receipts",{
 "requestKey":str(uuid4()),"sellerSku":sku,
 "quantity":1,"evidenceReference":"V76 local claimed arrival","origin":"MOCK"})
post(f"{fba}/{over}/dispatch",{})
post(f"{fba}/{over}/receipts",{
 "requestKey":str(uuid4()),"sellerSku":sku,
 "quantity":3,"evidenceReference":"V76 local over-report claim","origin":"MOCK"})
snapshot,rows=by_id()
assert len(rows)==5 and not snapshot["truncated"],snapshot
for ship,code,severity in [
 (confirmed,"IDENTITY_CONFIRMED","INFO"),
 (pending,"PENDING_IDENTITY_REVIEW","WARNING"),
 (unlinked,"UNLINKED_DRAFT","INFO"),
 (reported,"LOCAL_REPORT_WITHOUT_IDENTITY","WARNING"),
 (over,"LOCAL_OVER_REPORT","HIGH")]:
    assert rows[ship]["findingCode"]==code,(ship,rows[ship])
    assert rows[ship]["severity"]==severity,rows[ship]
assert snapshot["totals"]=={
 "examinedLines":5,"high":1,"warning":2,"informational":2,
 "pendingIdentity":1,"unlinked":3,"localVariance":1},snapshot["totals"]
assert rows[confirmed]["purchaseOrderNumber"]==po["order"]["orderNumber"]
assert rows[confirmed]["asnReportedQuantity"]==18
assert rows[confirmed]["locallyReportedQuantity"]==0
assert rows[unlinked]["purchaseLocalReceivedQuantity"] is None
assert rows[reported]["asnLocalReceivedQuantity"] is None

# Only an actual LOCAL receipt proves local warehouse arrival; never counted as
# an Amazon FBA receipt or shared per-shipment loss/shortage.
post(f"/api/v1/purchasing/orders/{order}/receive",{
 "lineId":poLine,"quantity":4,"warehouseId":warehouse,"asnId":asnId})
snapshot,rows=by_id()
assert rows[confirmed]["purchaseLocalReceivedQuantity"]==4
assert rows[confirmed]["asnLocalReceivedQuantity"]==4
assert rows[confirmed]["locallyReportedQuantity"]==0
assert rows[confirmed]["findingCode"]=="IDENTITY_CONFIRMED"

# Emulate a changed supplier claim in the isolated synthetic PG fixture:
# never silently treat a historical confirmation as a current SHIPPED ASN.
sql(f"UPDATE erp_purchase_asn SET status='CANCELLED' WHERE tenant_id='{tenant}' AND id='{asnId}'")
snapshot,rows=by_id()
assert rows[confirmed]["findingCode"]=="LINK_SOURCE_CHANGED",rows[confirmed]
assert rows[confirmed]["recommendedAction"]=="RECHECK_SOURCE_IDENTITY"
assert rows[pending]["findingCode"]=="PENDING_IDENTITY_REVIEW"
sql(f"UPDATE erp_purchase_asn SET status='SHIPPED' WHERE tenant_id='{tenant}' AND id='{asnId}'")
assert by_id()[1][confirmed]["findingCode"]=="IDENTITY_CONFIRMED"

# V65 independent Reviewer may accept a locally reported variance; the
# unresolved quantity over-report should stop being flagged HIGH, but this
# still does not establish a PO, carrier, or Amazon receipt identity.
post(f"{fba}/{over}/review",{})
post(f"{fba}/{over}/variances",{
 "sellerSku":sku,"note":"V76 local over-report verified as manual variance"},
 browser=reviewer,token=token)
post(f"{fba}/{over}/reconcile",{})
snapshot,rows=by_id()
assert rows[over]["localVarianceDecision"]=="ACCEPT_VARIANCE",rows[over]
assert rows[over]["findingCode"]=="LOCAL_REPORT_WITHOUT_IDENTITY",rows[over]
assert snapshot["totals"]["high"]==0
assert rows[over]["locallyReportedQuantity"]==3
assert sql(f"""SELECT COUNT(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="0"
print("PASS V76: PG18 authenticated 5-line board, 2 Owners, confirmed/pending/unlinked, local over-report, ASN state drift, QC-independent receipts, accepted variance, bounded source, zero FBA stock changes")
