#!/usr/bin/env python3
"""V70: synthetic localhost-only Orders observations; no live sellers, network writes or purchase orders."""
import http.cookiejar
import json
import os
import subprocess
from uuid import uuid4
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, HTTPCookieProcessor, build_opener

assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true" and os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
base="http://127.0.0.1:8080"
opener=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=None
def call(path,method="GET",payload=None,expected=200,form=False,headers=None):
    h={"Accept":"application/json",**(headers or {})}
    data=None
    if payload is not None:
        h["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
        data=(urlencode(payload) if form else json.dumps(payload)).encode()
    if csrf and method!="GET":h["X-CSRF-TOKEN"]=csrf
    try:
        with opener.open(Request(base+path,headers=h,data=data,method=method),timeout=20) as r:
            status,raw=r.status,r.read()
    except HTTPError as e:
        status,raw=e.code,e.read()
    assert status==expected,(path,status,expected,raw[:240])
    try:return json.loads(raw)
    except ValueError:return raw

def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    run=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-p",os.getenv("DB_PORT","5432"),
        "-U","merchivra","-d","merchivra","-Atc",statement],
        env=env,text=True,capture_output=True)
    assert run.returncode==0,run.stderr[-900:]
    return run.stdout.strip()

def post(path,payload,expected=200,headers=None):
    return call(path,"POST",payload,expected,headers=headers)
foreign=str(uuid4())
root="/api/v1/replenishment/marketplaces/"
call(root+foreign+"/demand-signals",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
tenant=call("/api/v1/auth/me")["activeTenantId"]
call(root+foreign+"/demand-signals",expected=404)

account=post("/api/v1/seller-accounts",
  {"displayName":"V70-ONLY-SYNTHETIC","region":"NA"},201,
  {"Idempotency-Key":"v70-demand-account"})["id"]
mp=post(f"/api/v1/seller-accounts/{account}/marketplaces",
  {"marketplaceId":"ATVPDKIKX0DER"},201)["id"]
api=root+mp
assert call(api+"/demand-signals")==[]

# Direct SQL source fixtures are explicitly limited to CI PG18's disposable DB.
product,listing,mapping,order,line,job,page=[str(uuid4()) for _ in range(7)]
sku="V70-DEMAND-SKU"
sql(f"""INSERT INTO product_sku (id,tenant_id,internal_sku,display_name)
 VALUES ('{product}','{tenant}','V70-INTERNAL','V70 fictitious');
 INSERT INTO marketplace_listing(id,tenant_id,seller_marketplace_id,seller_sku,data_origin)
 VALUES ('{listing}','{tenant}','{mp}','{sku}','MOCK');""")
post(api+"/policies",{"sellerSku":sku,"reorderPoint":3,"targetStock":20,"leadTimeDays":14})
def signal():
    rows=call(api+"/demand-signals")
    assert len(rows)==1,rows
    return rows[0]
first=signal()
assert first["status"]=="UNMAPPED" and first["observedUnits28d"] is None,first
sql(f"""INSERT INTO listing_sku_mapping
  (id,tenant_id,listing_id,product_sku_id,status,valid_from,mapping_version)
  VALUES('{mapping}','{tenant}','{listing}','{product}','ACTIVE',now(),1);
  UPDATE marketplace_listing SET mapping_status='ACTIVE' WHERE id='{listing}';""")
second=signal()
assert second["status"]=="INCOMPLETE_COVERAGE" and second["observedDailyUnits28d"] is None,second

sql(f"""INSERT INTO orders_sync_job(id,tenant_id,seller_marketplace_id,mode,state,
 window_after,window_before,last_safe_watermark)
 VALUES('{job}','{tenant}','{mp}','MOCK','SUCCEEDED',
   now()-interval '32 days',now()-interval '5 minutes',now());
 INSERT INTO orders_sync_page_commit
 (id,tenant_id,job_id,page_index,generation,record_count)
 VALUES('{page}','{tenant}','{job}',0,0,1);
 INSERT INTO sales_order(id,tenant_id,seller_marketplace_id,external_order_id,
 fulfillment_status_raw,order_time,source_updated_at,data_origin,items_observation)
 VALUES('{order}','{tenant}','{mp}','V70-SYNTHETIC-ORDER','SHIPPED',
 now()-interval '1 day',now()-interval '1 day','MOCK','OBSERVED');
 INSERT INTO order_item(id,tenant_id,sales_order_id,external_item_id,seller_sku_raw,
 quantity,data_origin)
 VALUES('{line}','{tenant}','{order}','V70-LINE-1','{sku}',28,'MOCK');""")
complete=signal()
assert complete["status"]=="MOCK_SAMPLE" and complete["observedOrderCount"]==1,complete
assert complete["observedUnits28d"]==28 and complete["observedUnits7d"]==28,complete
assert complete["observedDailyUnits28d"]=="1.000",complete
assert complete["illustrativeLeadTimeUnits"]==14,complete

sql(f"UPDATE sales_order SET items_observation='NOT_OBSERVED' WHERE id='{order}'")
incomplete=signal()
assert incomplete["status"]=="INCOMPLETE_ITEMS" and incomplete["observedUnits28d"] is None,incomplete
sql(f"UPDATE sales_order SET items_observation='OBSERVED' WHERE id='{order}'")

# A second order with mismatched origin invalidates the entire 28-day observation,
# even though the target SKU's original MOCK OrderItem still exists.
other=str(uuid4())
sql(f"""INSERT INTO sales_order(id,tenant_id,seller_marketplace_id,external_order_id,
 fulfillment_status_raw,order_time,data_origin,items_observation)
 VALUES('{other}','{tenant}','{mp}','V70-WRONG-ORIGIN','SHIPPED',
 now()-interval '1 day','LIVE','OBSERVED');""")
mixed=signal()
assert mixed["status"]=="INCOMPLETE_ITEMS" and mixed["observedDailyUnits28d"] is None,mixed
sql(f"DELETE FROM sales_order WHERE id='{other}'")

sql(f"DELETE FROM order_item WHERE id='{line}'; DELETE FROM sales_order WHERE id='{order}';")
empty=signal()
assert empty["status"]=="NO_OBSERVED_ORDERS" and empty["observedUnits28d"]==0,empty
assert empty["observedDailyUnits28d"] is None,empty

# A not-succeeded checkpoint means "unknown", not zero; no local purchasing writes.
sql(f"UPDATE orders_sync_job SET state='PAUSED' WHERE id='{job}'")
uncovered=signal()
assert uncovered["status"]=="INCOMPLETE_COVERAGE" and uncovered["observedUnits28d"] is None
assert sql(f"SELECT COUNT(*) FROM erp_replenishment_purchase_draft_source WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'")=="0"
print("PASS V70: scoped HTTP/PG18 mapping gate, incomplete sync, full MOCK 28-day sample, missing OrderItems, mixed origin, covered zero vs unknown and no PO writes")
