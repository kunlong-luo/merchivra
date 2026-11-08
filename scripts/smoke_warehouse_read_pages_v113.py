#!/usr/bin/env python3
"""V113 disposable PG18 HTTP milestone: 525 stock SKU keys and 525 pending PO lines.

Guarded localhost-only synthetic data. Runs in CI full_suite, never quick PR.
No Seller OAuth, SP-API, real data or remote Amazon writes.
"""
import base64
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, HTTPCookieProcessor, build_opener
from uuid import uuid4

assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("GITHUB_ACTIONS")=="true" or os.getenv("MERCHIVRA_DISPOSABLE_TEST_DB")=="true"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES","false")=="false"

http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=""
def sql(query):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    run=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
        env=env,capture_output=True,text=True,check=False)
    assert run.returncode==0,(query[:200],run.stderr[-1000:])
    return run.stdout.strip()

def request(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,
            method=method,headers=headers,data=data),timeout=30) as res:
            status,payload,cache=res.status,res.read(),res.headers.get("Cache-Control","")
    except HTTPError as exc:
        status,payload,cache=exc.code,exc.read(),""
    assert status==expected,(path,status,expected,payload[:900])
    if method=="GET" and ("/page" in path or "/item" in path) and status==200:
        assert "no-store" in cache,(path,cache)
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload

def get(path,**params):return request(path+"?"+urlencode(params))
stock_base="/api/v1/purchasing/warehouse-stock/page"
item_base="/api/v1/purchasing/warehouse-stock/item"
pending_base="/api/v1/purchasing/pending-receipts/page"
request(stock_base+"?warehouseId="+str(uuid4()),expected=401)
request(pending_base+"?limit=50",expected=401)
csrf=request("/api/v1/auth/csrf")["token"]
request("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=request("/api/v1/auth/csrf")["token"]
me=request("/api/v1/auth/me")
assert me["isOwner"]
tenant,actor=me["activeTenantId"],me["userId"]

warehouse=request("/api/v1/purchasing/warehouses","POST",{
    "warehouseCode":"V113WHMAIN",
    "displayName":"V113 Disposable Main Stock Warehouse"},201)["id"]
other=request("/api/v1/purchasing/warehouses","POST",{
    "warehouseCode":"V113WHOTHER",
    "displayName":"V113 Disposable Empty Stock Warehouse"},201)["id"]

# Every SKU receives a valid signed COUNT_ADJUSTMENT through a source operation.
# A separate pending-only active SKU deliberately has *observed* zero movement.
sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'V113-STOCK-'||lpad(i::text,4,'0'),
  'V113 synthetic local stock SKU '||i
 FROM generate_series(1,525) AS i""")
pending_sku=sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 VALUES(gen_random_uuid(),'{tenant}'::uuid,
  'V113-PENDING-SKU','V113 synthetic pending SKU') RETURNING id""")
sql(f"""INSERT INTO erp_local_stock_operation(
 id,tenant_id,request_key,operation_type,source_warehouse_id,product_sku_id,
 requested_quantity,expected_on_hand,applied_delta,after_quantity,reason,created_by)
 SELECT gen_random_uuid(),s.tenant_id,gen_random_uuid(),'COUNT',
  '{warehouse}'::uuid,s.id,1,0,1,1,'V113 synthetic stock seed', '{actor}'::uuid
 FROM product_sku s WHERE s.tenant_id='{tenant}'
 AND s.internal_sku LIKE 'V113-STOCK-%'""")
sql(f"""INSERT INTO erp_local_stock_movement(
 id,tenant_id,warehouse_id,product_sku_id,operation_id,movement_type,
 quantity,actor_user_id)
 SELECT gen_random_uuid(),tenant_id,source_warehouse_id,product_sku_id,
 id,'COUNT_ADJUSTMENT',1,created_by
 FROM erp_local_stock_operation WHERE tenant_id='{tenant}'
 AND source_warehouse_id='{warehouse}' AND reason='V113 synthetic stock seed'""")
assert sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement m
 JOIN product_sku s ON s.tenant_id=m.tenant_id AND s.id=m.product_sku_id
 WHERE m.tenant_id='{tenant}' AND m.warehouse_id='{warehouse}'
 AND s.internal_sku LIKE 'V113-STOCK-%'""")=="525"

supplier=request("/api/v1/purchasing/suppliers","POST",{
    "supplierCode":"V113SUPP",
    "displayName":"V113 disposable purchase supplier"},201)["id"]
sql(f"""INSERT INTO erp_purchase_order(
 id,tenant_id,supplier_id,order_number,currency_code,status,created_by)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{supplier}'::uuid,
  'PO-V113-PEND-'||lpad(i::text,4,'0'),'USD','APPROVED',
  '{actor}'::uuid FROM generate_series(1,525) AS i""")
sql(f"""INSERT INTO erp_purchase_order_line(
 id,tenant_id,purchase_order_id,product_sku_id,ordered_quantity,unit_cost)
 SELECT gen_random_uuid(),tenant_id,id,'{pending_sku}'::uuid,10,3.25
 FROM erp_purchase_order WHERE tenant_id='{tenant}'
 AND order_number LIKE 'PO-V113-PEND-%'""")
# Synthetic partial receipt: aggregation must be evaluated before filtering
# out fully received lines; do not infer shipment/ASN from this source.
sql(f"""INSERT INTO erp_purchase_receipt(
 id,tenant_id,purchase_order_id,purchase_order_line_id,quantity,received_by,warehouse_id)
 SELECT gen_random_uuid(),l.tenant_id,l.purchase_order_id,l.id,3,'{actor}'::uuid,
  '{warehouse}'::uuid
 FROM erp_purchase_order_line l
 JOIN erp_purchase_order o ON o.tenant_id=l.tenant_id AND o.id=l.purchase_order_id
 WHERE o.tenant_id='{tenant}' AND o.order_number='PO-V113-PEND-0001'""")
sql(f"""UPDATE erp_purchase_order SET status='RECEIVING'
 WHERE tenant_id='{tenant}' AND order_number='PO-V113-PEND-0001'""")
assert sql(f"""SELECT COUNT(*) FROM erp_purchase_order_line l
 JOIN erp_purchase_order o ON o.tenant_id=l.tenant_id AND o.id=l.purchase_order_id
 WHERE o.tenant_id='{tenant}' AND o.order_number LIKE 'PO-V113-PEND-%'""")=="525"

def all_pages(base,params):
    pages=[]
    cursor=None
    for i in range(11):
        page=get(base,**params,**({"cursor":cursor} if cursor else {}))
        pages.append(page)
        assert page["pageSize"]==50
        cursor=page["nextCursor"]
    assert [len(p["items"]) for p in pages]==[50]*10+[25]
    assert all(p["hasMore"] for p in pages[:10])
    assert not pages[10]["hasMore"] and cursor is None
    return pages

stocks=all_pages(stock_base,{"warehouseId":warehouse,"limit":50,"search":"V113-STOCK-"})
all_stock=[r for p in stocks for r in p["items"]]
assert len({r["productSkuId"] for r in all_stock})==525
assert [r["internalSku"] for r in all_stock]==sorted(r["internalSku"] for r in all_stock)
assert all(r["onHandQuantity"]==1 and r["recordedReceivedQuantity"]==0
 for r in all_stock)
late=all_stock[500]
assert late["internalSku"]=="V113-STOCK-0501"
assert get(item_base,warehouseId=warehouse,productSkuId=late["productSkuId"])==late
zero=get(item_base,warehouseId=warehouse,productSkuId=pending_sku)
assert zero["onHandQuantity"]==0 and zero["productSkuId"]==pending_sku
request(item_base+"?"+urlencode({"warehouseId":warehouse,
    "productSkuId":str(uuid4())}),expected=404)
request(item_base+"?"+urlencode({"warehouseId":str(uuid4()),
    "productSkuId":late["productSkuId"]}),expected=404)
assert len(request("/api/v1/purchasing/warehouse-stock?warehouseId="+warehouse))<=500

pending_pages=all_pages(pending_base,{"limit":50,"search":"PO-V113-PEND-"})
all_pending=[r for p in pending_pages for r in p["items"]]
assert len({r["purchaseOrderLineId"] for r in all_pending})==525
assert [r["orderNumber"] for r in all_pending]==sorted(
 r["orderNumber"] for r in all_pending)
assert all_pending[0]["orderNumber"]=="PO-V113-PEND-0001"
assert (all_pending[0]["receivedQuantity"],all_pending[0]["remainingQuantity"])==(3,7)
assert all(r["remainingQuantity"]==10 for r in all_pending[1:])
assert all_pending[500]["orderNumber"]=="PO-V113-PEND-0501"
assert len(get(pending_base,limit=50,status="RECEIVING",
    search="PO-V113-PEND-")["items"])==1
assert len(get(pending_base,limit=50,status="APPROVED",
    search="PO-V113-PEND-")["items"])==50
assert len(get(pending_base,limit=50,search="PO-V113-PEND-0525")["items"])==1
assert get(pending_base,limit=50,search="PO%")["items"]==[]
assert len(request("/api/v1/purchasing/pending-receipts"))<=500

def error(base,defaults,**args):
    request(base+"?"+urlencode({**defaults,**args}),expected=400)
stock_filters={"warehouseId":warehouse,"limit":50,"search":"V113-STOCK-"}
error(stock_base,stock_filters,limit=0)
error(stock_base,stock_filters,limit=101)
error(stock_base,stock_filters,search="SKU%")
error(stock_base,stock_filters,cursor="bad*")
error(stock_base,stock_filters,cursor=stocks[0]["nextCursor"],warehouseId=other)
error(stock_base,stock_filters,cursor=stocks[0]["nextCursor"],search="V113")
pending_filters={"limit":50,"search":"PO-V113-PEND-"}
error(pending_base,pending_filters,limit=0)
error(pending_base,pending_filters,limit=101)
error(pending_base,pending_filters,status="INVALID")
error(pending_base,pending_filters,search="PO%")
error(pending_base,pending_filters,cursor="bad*")
error(pending_base,pending_filters,cursor=pending_pages[0]["nextCursor"],
      status="APPROVED")
error(pending_base,pending_filters,cursor=pending_pages[0]["nextCursor"],
      search="PO-V113")
for token,base,defaults in (
    (stocks[0]["nextCursor"],stock_base,stock_filters),
    (pending_pages[0]["nextCursor"],pending_base,pending_filters)):
    parts=base64.urlsafe_b64decode(token+"="*((-len(token))%4)).decode().split("\x1f")
    assert len(parts)==6 and parts[1]==tenant
    parts[1]=str(uuid4())
    forged=base64.urlsafe_b64encode("\x1f".join(parts).encode()).decode().rstrip("=")
    error(base,defaults,cursor=forged)
print("PASS V113: 525 paginated local stock SKUs and 525 pending PO lines;"
      " observed exact 0/1, 501st item, signed source, scope/search/cursor, no-store")
