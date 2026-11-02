#!/usr/bin/env python3
"""V74 localhost PG18 HTTP: trusted historical order rate + FBA mode + local PO.
Synthetic only: never touches real seller credentials, orders, or API egress.
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
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
host="http://127.0.0.1:8080"
owner=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=None
def call(path,method="GET",payload=None,expected=200,form=False,
         headers=None,browser=owner,token=None):
    h={"Accept":"application/json",**(headers or {})}
    data=None
    if payload is not None:
        data=(urlencode(payload) if form else json.dumps(payload)).encode()
        h["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and (token or csrf):h["X-CSRF-TOKEN"]=token or csrf
    try:
        with browser.open(Request(host+path,data=data,headers=h,method=method),timeout=25) as r:
            status,raw=r.status,r.read()
    except HTTPError as e:
        status,raw=e.code,e.read()
    assert status==expected,(method,path,status,expected,raw[:600])
    try:return json.loads(raw)
    except ValueError:return raw
def post(path,payload,expected=200,headers=None,browser=owner,token=None):
    return call(path,"POST",payload,expected,headers=headers,browser=browser,token=token)
def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    r=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1","-h","127.0.0.1",
       "-p",os.getenv("DB_PORT","5432"),"-U","merchivra","-d","merchivra","-Atc",statement],
       text=True,capture_output=True,env=env)
    assert r.returncode==0,r.stderr[-1200:]
    return r.stdout.strip()

api="/api/v1/replenishment/marketplaces/"
foreign=str(uuid4())
call(api+foreign+"/priorities",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
post("/api/v1/auth/login",{"username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me");tenant=me["activeTenantId"]
assert me["isOwner"]
call(api+foreign+"/priorities",expected=404)

acct=post("/api/v1/seller-accounts",{
 "displayName":"V74-DISPOSABLE-PERF","region":"NA"},201,
 {"Idempotency-Key":"v74-priority-account"})["id"]
mp=post("/api/v1/seller-accounts/"+acct+"/marketplaces",
 {"marketplaceId":"ATVPDKIKX0DER"},201)["id"]
route=api+mp
assert call(route+"/priorities")==[]
sku="V74-MOCK-PRIORITY"
product,listing,mapping,obs,job,page,sale,item=[str(uuid4()) for _ in range(8)]
sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 VALUES('{product}','{tenant}','V74-PRODUCT','V74 fictitious');
 INSERT INTO marketplace_listing(id,tenant_id,seller_marketplace_id,seller_sku,
  mapping_status,data_origin)
 VALUES('{listing}','{tenant}','{mp}','{sku}','ACTIVE','MOCK');
 INSERT INTO listing_sku_mapping(id,tenant_id,listing_id,product_sku_id,
  status,valid_from,mapping_version)
 VALUES('{mapping}','{tenant}','{listing}','{product}','ACTIVE',now(),1);""")
policy=post(route+"/policies",{
 "sellerSku":sku,"reorderPoint":3,"targetStock":20,"leadTimeDays":14})
def one():
    p=call(route+"/priorities")
    assert len(p)==1,p
    return p[0]
first=one()
assert first["evidenceLevel"]=="STOCK_UNTRUSTED" and first["priorityCode"]=="BLOCKED",first
assert first["illustrativeCoverageDays"] is None,first

sql(f"""INSERT INTO fba_inventory_observation
 (id,tenant_id,seller_marketplace_id,seller_sku_raw,source_observation_key,
  quality,fulfillable,reserved,observed_at,data_origin)
 VALUES('{obs}','{tenant}','{mp}','{sku}','V74-FRESH-OBS','OBSERVED',2,0,now(),'MOCK');""")
second=one()
assert second["stockState"]=="REORDER" and second["evidenceLevel"]=="DEMAND_INCOMPLETE",second
assert second["illustrativeCoverageDays"] is None,second

supplier=post("/api/v1/purchasing/suppliers",{
 "supplierCode":"V74SUPPLIER","displayName":"V74 Synthetic Supplier"},201)["id"]
rule=post("/api/v1/purchasing/planning/supplier-rules",{
 "supplierId":supplier,"productSkuId":product,
 "minimumOrderQuantity":25,"packMultiple":12,"leadTimeDays":17,
 "currency":"USD","unitCost":"4.250000"})
quoted=call(route+"/supplier-quotes")
assert len(quoted)==1 and quoted[0]["status"]=="READY" and quoted[0]["orderQuantity"]==36,quoted
assert one()["readySupplierQuoteCount"]==1

# V70 source gate: committed MOCK job and fully OBSERVED OrderItems in 28d.
sql(f"""INSERT INTO orders_sync_job(id,tenant_id,seller_marketplace_id,mode,state,
 window_after,window_before,last_safe_watermark)
 VALUES('{job}','{tenant}','{mp}','MOCK','SUCCEEDED',
 now()-interval '32 days',now()-interval '5 minutes',now());
 INSERT INTO orders_sync_page_commit
 (id,tenant_id,job_id,page_index,generation,record_count)
 VALUES('{page}','{tenant}','{job}',0,0,1);
 INSERT INTO sales_order(id,tenant_id,seller_marketplace_id,external_order_id,
 fulfillment_status_raw,order_time,source_updated_at,data_origin,items_observation)
 VALUES('{sale}','{tenant}','{mp}','V74-SYNTHETIC-ONLY','SHIPPED',
 now()-interval '1 day',now()-interval '1 day','MOCK','OBSERVED');
 INSERT INTO order_item(id,tenant_id,sales_order_id,external_item_id,
 seller_sku_raw,quantity,data_origin)
 VALUES('{item}','{tenant}','{sale}','V74-LINE','{sku}',28,'MOCK');""")
good=one()
assert good["stockSourceMode"]=="MOCK" and good["demandSourceMode"]=="MOCK",good
assert good["evidenceLevel"]=="MOCK_SAMPLE" and good["riskCode"]=="LEAD_WINDOW_EXPOSURE",good
assert good["priorityCode"]=="HIGH" and good["illustrativeCoverageDays"]=="2.0",good
assert good["illustrativePolicyLeadUnits"]==14 and good["observedUnits28d"]==28,good
assert good["readySupplierQuoteCount"]==1 and good["shortestSupplierTermDays"]==17,good
assert good["localDraftOrSubmittedUnits"]==0 and good["localApprovedUnreceivedUnits"]==0,good

# V71 creates a real LOCAL DRAFT; V74 must never subtract it from FBA observed 2.
q=quoted[0]
body={"requestKey":str(uuid4()),"sellerSku":sku,"supplierId":supplier,
 "currency":q["currency"],"unitCost":q["unitCost"],
 "expectedPolicyUpdatedAt":policy["updatedAt"],"expectedObservationId":obs,
 "expectedQuantity":18,"expectedSupplierRuleUpdatedAt":q["supplierRuleUpdatedAt"],
 "expectedOrderQuantity":36}
draft=post(route+"/purchase-drafts",body,201)
assert draft["order"]["status"]=="DRAFT"
oid=draft["order"]["id"];line=draft["lines"][0]["id"]
before=one()
assert before["localDraftOrSubmittedUnits"]==36 and before["localApprovedUnreceivedUnits"]==0,before
assert before["existingLocalOrderNeedsReview"] and before["localPendingOrderCount"]==1,before
assert before["fulfillable"]==2 and before["illustrativeCoverageDays"]=="2.0",before
post("/api/v1/purchasing/orders/"+oid+"/submit",{})

# A distinct Owner must approve. The dashboard must show approved-but-not-received,
# not create Amazon inbound or count the PO as sellable FBA inventory.
inv=post("/api/v1/team/invitations",{"loginName":"v74-ci-approver"})
reviewer=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
token=call("/api/v1/auth/csrf",browser=reviewer)["token"]
pwd="v74-ci-disposable-reviewer-credential"
post("/api/v1/auth/invitations/accept",{"token":inv["token"],"password":pwd},
 204,browser=reviewer,token=token)
token=call("/api/v1/auth/csrf",browser=reviewer)["token"]
call("/api/v1/auth/login","POST",{"username":"v74-ci-approver","password":pwd},
 form=True,browser=reviewer,token=token)
token=call("/api/v1/auth/csrf",browser=reviewer)["token"]
assert call("/api/v1/auth/me",browser=reviewer)["userId"]!=me["userId"]
post("/api/v1/purchasing/orders/"+oid+"/approve",{},browser=reviewer,token=token)
approved=one()
assert approved["localDraftOrSubmittedUnits"]==0 and approved["localApprovedUnreceivedUnits"]==36,approved
assert approved["fulfillable"]==2 and approved["priorityCode"]=="HIGH",approved

warehouse=post("/api/v1/purchasing/warehouses",{
 "warehouseCode":"V74LOCAL","displayName":"V74 Synthetic Warehouse"},201)
post("/api/v1/purchasing/orders/"+oid+"/receive",{
 "lineId":line,"quantity":5,"warehouseId":warehouse["id"],"asnId":None})
partial=one()
assert partial["localApprovedUnreceivedUnits"]==31 and partial["localReceivedUnits"]==5,partial
assert partial["fulfillable"]==2 and partial["illustrativeCoverageDays"]=="2.0",partial

# MOCK/LIVE provenance collision must suppress *all* coverage math.
sql(f"UPDATE fba_inventory_observation SET data_origin='LIVE' WHERE id='{obs}'")
mixed=one()
assert mixed["evidenceLevel"]=="SOURCE_MISMATCH" and mixed["priorityCode"]=="BLOCKED",mixed
assert mixed["illustrativeCoverageDays"] is None and mixed["illustrativePolicyLeadUnits"] is None,mixed
assert mixed["localApprovedUnreceivedUnits"]==31,mixed
sql(f"UPDATE fba_inventory_observation SET data_origin='MOCK' WHERE id='{obs}'")
assert one()["evidenceLevel"]=="MOCK_SAMPLE"

# Unknown FBA quantity cannot be inferred from local received goods or orders.
sql(f"UPDATE fba_inventory_observation SET quality='UNKNOWN',fulfillable=NULL WHERE id='{obs}'")
unknown=one()
assert unknown["stockState"]=="INCOMPLETE_OBSERVATION" and unknown["fulfillable"] is None,unknown
assert unknown["illustrativeCoverageDays"] is None and unknown["evidenceLevel"]=="STOCK_UNTRUSTED",unknown
assert unknown["localReceivedUnits"]==5
assert sql(f"SELECT COUNT(*) FROM erp_purchase_order WHERE tenant_id='{tenant}' AND id='{oid}'")=="1"
assert sql(f"SELECT COUNT(*) FROM erp_replenishment_purchase_draft_source WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'")=="1"
print("PASS V74: PG18 Owner Session + real 28d MOCK items, FBA quality gate, MOQ supplier terms, independent Owner PO approval, partial receipts, mixed origins, unknown stock, no extra POs or FBA writes")
