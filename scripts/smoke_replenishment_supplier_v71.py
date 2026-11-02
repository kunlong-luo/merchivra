#!/usr/bin/env python3
"""V71 isolated PG18 / HTTP human-only FBA -> V42 supplier terms -> V37 PO DRAFT.

Requires explicitly disposable localhost Mock CI. Never connects to Amazon,
sends supplier orders, approves a PO, or treats unverified inbound as stock.
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
base="http://127.0.0.1:8080"
client=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=None

def call(path,method="GET",body=None,expected=200,form=False,headers=None):
    h={"Accept":"application/json",**(headers or {})}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        h["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if csrf and method!="GET":h["X-CSRF-TOKEN"]=csrf
    try:
        with client.open(Request(base+path,headers=h,data=data,method=method),timeout=20) as r:
            status,payload=r.status,r.read()
    except HTTPError as err:
        status,payload=err.code,err.read()
    assert status==expected,(method,path,status,expected,payload[:300])
    try:return json.loads(payload)
    except ValueError:return payload

def post(path,data,expected=200,headers=None):
    return call(path,"POST",data,expected,headers=headers)

def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    proc=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1","-h","127.0.0.1",
      "-p",os.getenv("DB_PORT","5432"),"-U","merchivra","-d","merchivra","-Atc",statement],
      env=env,text=True,capture_output=True)
    assert proc.returncode==0,proc.stderr[-800:]
    return proc.stdout.strip()

root="/api/v1/replenishment/marketplaces/"
foreign=str(uuid4())
call(root+foreign+"/supplier-quotes",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
tenant=call("/api/v1/auth/me")["activeTenantId"]
call(root+foreign+"/supplier-quotes",expected=404)

account=post("/api/v1/seller-accounts",{
  "displayName":"V71-DISPOSABLE-ONLY","region":"NA"},201,
  {"Idempotency-Key":"v71-quote-ci-account"})["id"]
mp=post("/api/v1/seller-accounts/"+account+"/marketplaces",
 {"marketplaceId":"ATVPDKIKX0DER"},201)["id"]
route=root+mp
assert call(route+"/supplier-quotes")==[]
product,listing,mapping,obs=[str(uuid4()) for _ in range(4)]
sku="V71-FBA-QUOTE-SKU"
sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 VALUES('{product}','{tenant}','V71-PRODUCT','V71 fictional');
 INSERT INTO marketplace_listing(id,tenant_id,seller_marketplace_id,seller_sku,
   mapping_status,data_origin)
 VALUES('{listing}','{tenant}','{mp}','{sku}','ACTIVE','MOCK');
 INSERT INTO listing_sku_mapping(id,tenant_id,listing_id,product_sku_id,
   status,valid_from,mapping_version)
 VALUES('{mapping}','{tenant}','{listing}','{product}','ACTIVE',now(),1);
 INSERT INTO fba_inventory_observation(id,tenant_id,seller_marketplace_id,
   seller_sku_raw,source_observation_key,quality,fulfillable,reserved,observed_at,data_origin)
 VALUES('{obs}','{tenant}','{mp}','{sku}','V71-FBA-FRESH','OBSERVED',2,0,now(),'MOCK');""")
policy=post(route+"/policies",{
 "sellerSku":sku,"reorderPoint":3,"targetStock":20,"leadTimeDays":14})
assert policy["targetStock"]==20
sup=post("/api/v1/purchasing/suppliers",{
 "supplierCode":"V71SUP1","displayName":"V71 Fake Supplier"},201)["id"]
assert call(route+"/supplier-quotes")==[],"No V42 rule must not invent pricing"
rule=post("/api/v1/purchasing/planning/supplier-rules",{
 "supplierId":sup,"productSkuId":product,
 "minimumOrderQuantity":25,"packMultiple":12,
 "leadTimeDays":17,"currency":"USD","unitCost":"3.250000"})
def quote():
    options=call(route+"/supplier-quotes")
    assert len(options)==1,options
    return options[0]
q=quote()
assert q["status"]=="READY" and q["baseSuggestedQuantity"]==18,q
assert q["orderQuantity"]==36 and q["minimumOrderQuantity"]==25,q
assert q["packMultiple"]==12 and q["supplierLeadTimeDays"]==17,q
assert q["currency"]=="USD" and q["estimatedTotal"]=="117.000000",q
assert q["observationId"]==obs and q["supplierRuleUpdatedAt"]==rule["updatedAt"],q

draft_route=route+"/purchase-drafts"
key=str(uuid4())
body={"requestKey":key,"sellerSku":sku,"supplierId":sup,"currency":"USD",
 "unitCost":q["unitCost"],"expectedPolicyUpdatedAt":policy["updatedAt"],
 "expectedObservationId":obs,"expectedQuantity":18,
 "expectedSupplierRuleUpdatedAt":q["supplierRuleUpdatedAt"],
 "expectedOrderQuantity":36}

post(draft_route,{**body,"expectedOrderQuantity":37},expected=409)
post(draft_route,{**body,"unitCost":"3.260000"},expected=409)
post(draft_route,{**body,"currency":"EUR"},expected=409)
post(draft_route,{**body,"expectedSupplierRuleUpdatedAt":None},expected=400)
post(draft_route,{**body,"expectedOrderQuantity":None},expected=400)
assert call(draft_route)==[],"Invalid quote must not create POs"

# Rule edit after UI preview invalidates the old version, price, and quote.
new_rule=post("/api/v1/purchasing/planning/supplier-rules",{
 "supplierId":sup,"productSkuId":product,
 "minimumOrderQuantity":25,"packMultiple":12,
 "leadTimeDays":18,"currency":"USD","unitCost":"4.000000"})
assert new_rule["updatedAt"]!=rule["updatedAt"],(new_rule,rule)
post(draft_route,body,expected=409)
q=quote()
assert q["unitCost"]=="4.000000" and q["supplierLeadTimeDays"]==18,q
body={**body,"unitCost":q["unitCost"],
 "expectedSupplierRuleUpdatedAt":q["supplierRuleUpdatedAt"]}
created=post(draft_route,body,expected=201)
assert created["order"]["status"]=="DRAFT",created
assert created["lines"][0]["orderedQuantity"]==36,created
assert created["lines"][0]["unitCost"]=="4.000000",created
same=post(draft_route,body,expected=201)
assert same["order"]["id"]==created["order"]["id"],"same-key retry must be idempotent"
post(draft_route,{**body,"unitCost":"3.250000"},expected=409)
post(draft_route,{**body,"requestKey":str(uuid4())},expected=409)
history=call(draft_route)
assert len(history)==1 and history[0]["pricingSource"]=="V42_RULE",history
assert history[0]["suggestedQuantity"]==18 and history[0]["orderedQuantity"]==36,history
assert history[0]["minimumOrderQuantity"]==25 and history[0]["packMultiple"]==12,history
assert history[0]["supplierLeadTimeDays"]==18 and history[0]["supplierRuleUpdatedAt"]==q["supplierRuleUpdatedAt"],history
assert sql(f"SELECT COUNT(*) FROM erp_purchase_order WHERE tenant_id='{tenant}' AND id='{created['order']['id']}' AND status='DRAFT'")=="1"

# Unknown inventory never becomes a price-backed purchase opportunity.
sql(f"UPDATE fba_inventory_observation SET quality='UNKNOWN',fulfillable=NULL WHERE id='{obs}'")
bad=quote()
assert bad["status"]=="INCOMPLETE_OBSERVATION" and bad["orderQuantity"] is None,bad
assert bad["estimatedTotal"] is None,bad
print("PASS V71: PG18+HTTP quote MOQ/pack/currency/source, version edits, 409 stale/duplicate, immutable purchase proof and DRAFT-only human action")
