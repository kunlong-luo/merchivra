#!/usr/bin/env python3
"""V92 follow-on: full-store LOCAL purchasing, bins and receipt lot action pages.
Only disposable localhost PostgreSQL 18 + authenticated Mock HTTP. NEVER Amazon.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, HTTPCookieProcessor, build_opener
from uuid import uuid4

assert os.getenv("DB_HOST") == "127.0.0.1" and os.getenv("DB_NAME") == "merchivra"
assert os.getenv("MERCHIVRA_DEMO_ENABLED") == "true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED") == "false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES", "false") == "false"

http = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = ""
def sql(query):
    env = {**os.environ, "PGPASSWORD": os.environ["DB_PASSWORD"]}
    p = subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
        env=env,text=True,capture_output=True,check=False)
    assert p.returncode == 0, (query[:250],p.stderr[-1600:])
    return p.stdout.strip()

def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and csrf: headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,
             method=method,headers=headers,data=data),timeout=45) as response:
            status,raw,cache=response.status,response.read(),response.headers.get("Cache-Control","")
    except HTTPError as exc:
        status,raw,cache=exc.code,exc.read(),""
    assert status==expected,(method,path,status,expected,raw[:800])
    if method=="GET" and "/page" in path and status==200:
        assert "no-store" in cache,(path,cache)
    try:return json.loads(raw)
    except (ValueError,TypeError):return raw

def get(path,**query):return call(path+"?"+urlencode(query))

def traverse(path,**filters):
    cursor=None
    results=[]
    pages=0
    while True:
        query={"limit":50,**filters}
        if cursor is not None:query["cursor"]=cursor
        data=get(path,**query)
        pages+=1
        assert pages<=50,(path,"unexpected infinite pages")
        assert data["pageSize"]==50
        assert len(data["items"])<=50
        results.extend(data["items"])
        if not data["hasMore"]:
            assert data["nextCursor"] is None
            break
        cursor=data["nextCursor"]
        assert isinstance(cursor,str) and cursor
    return results,pages

rules="/api/v1/purchasing/planning/supplier-rules"
call(rules+"/page?limit=50",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me")
assert me["isOwner"]
tenant=me["activeTenantId"]
actor=me["userId"]
supplier=sql(f"""SELECT id FROM erp_supplier WHERE tenant_id='{tenant}'
    AND supplier_code='V91SUPPLIER'""")
assert supplier

# 512 genuinely distinct local SKUs and rules, never copied from an Amazon account.
sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
SELECT gen_random_uuid(),'{tenant}'::uuid,
 'V92-PAGED-'||lpad(i::text,3,'0'),'V92 disposable local SKU '||i
FROM generate_series(1,512) AS i""")
sql(f"""INSERT INTO erp_supplier_sku_rule(
tenant_id,supplier_id,product_sku_id,minimum_order_quantity,pack_multiple,
lead_time_days,currency_code,unit_cost,updated_by)
SELECT '{tenant}'::uuid,'{supplier}'::uuid,id,1,1,14,'USD',1.00,'{actor}'::uuid
FROM product_sku WHERE tenant_id='{tenant}' AND internal_sku LIKE 'V92-PAGED-%'""")
rows,page_count=traverse(rules+"/page")
assert page_count>=11
sku_ids={r["productSkuId"] for r in rows if r["supplierId"]==supplier}
assert len(sku_ids)>=512, len(sku_ids)
assert len({(r["supplierId"],r["productSkuId"]) for r in rows})==len(rows)
assert len(call(rules))<=500
for invalid in ({"limit":0},{"limit":101},{"cursor":"bad%"},
                {"cursor":"a"*2100}):
    call(rules+"/page?"+urlencode(invalid),expected=400)

# A fresh LOCAL warehouse; no Amazon marketplace or tenant passed by the UI.
warehouse=call("/api/v1/purchasing/warehouses","POST",{
    "warehouseCode":"V92PAGEWAREHOUSE","displayName":"V92 disposable page warehouse"
},201)["id"]
sql(f"""INSERT INTO erp_local_stock_policy(
tenant_id,warehouse_id,product_sku_id,reorder_point,target_stock,updated_by)
SELECT '{tenant}'::uuid,'{warehouse}'::uuid,id,5,20,'{actor}'::uuid
FROM product_sku WHERE tenant_id='{tenant}' AND internal_sku LIKE 'V92-PAGED-%'""")
rec_base=f"/api/v1/purchasing/planning/warehouses/{warehouse}/recommendations"
recommendations,pages=traverse(rec_base+"/page")
assert len(recommendations)==512 and pages==11,(len(recommendations),pages)
assert {r["internalSku"] for r in recommendations}=={
    "V92-PAGED-"+str(i).zfill(3) for i in range(1,513)}
assert len(call(rec_base))<=500
other=str(uuid4())
first=get(rec_base+"/page",limit=50)
call(f"/api/v1/purchasing/planning/warehouses/{other}/recommendations/page?"+
     urlencode({"cursor":first["nextCursor"]}),expected=400)

# Warehouse-scoped local QC bins: 215 names, not the former first 200.
sql(f"""INSERT INTO erp_local_stock_bin(
id,tenant_id,warehouse_id,bin_code,purpose,created_by)
SELECT gen_random_uuid(),'{tenant}'::uuid,'{warehouse}'::uuid,
 'QC-RACK-'||lpad(i::text,3,'0'),'QC_HOLD','{actor}'::uuid
FROM generate_series(1,215) AS i""")
bins_base=f"/api/v1/purchasing/lot-locations/warehouses/{warehouse}/bins"
bins,pages=traverse(bins_base+"/page")
assert pages>=5
assert "QC-RACK-215" in {r["code"] for r in bins}
assert len(call(bins_base))<=200
rack=next(r["id"] for r in bins if r["code"]=="QC-RACK-215")
call(f"/api/v1/purchasing/lot-locations/warehouses/{other}/bins/page?"+
     urlencode({"cursor":get(bins_base+"/page")["nextCursor"]}),expected=400)

# Seed local PO/receipts/movements only inside the disposable PG18 CI database.
# This is fixture setup; GET pages must remain read-only and never approve a PO.
po=str(uuid4())
sql(f"""INSERT INTO erp_purchase_order(
id,tenant_id,supplier_id,order_number,currency_code,status,created_by)
VALUES('{po}','{tenant}','{supplier}','V92-PAGED-FIXTURE','USD','RECEIVING','{actor}')""")
sql(f"""INSERT INTO erp_purchase_order_line(
id,tenant_id,purchase_order_id,product_sku_id,ordered_quantity,unit_cost)
SELECT gen_random_uuid(),'{tenant}'::uuid,'{po}'::uuid,id,20,1.00
FROM product_sku WHERE tenant_id='{tenant}'
AND internal_sku BETWEEN 'V92-PAGED-001' AND 'V92-PAGED-125'""")
sql(f"""INSERT INTO erp_purchase_receipt(
id,tenant_id,purchase_order_id,purchase_order_line_id,quantity,received_by,warehouse_id)
SELECT gen_random_uuid(),'{tenant}'::uuid,'{po}'::uuid,l.id,1,'{actor}'::uuid,'{warehouse}'::uuid
FROM erp_purchase_order_line l WHERE l.tenant_id='{tenant}'
AND l.purchase_order_id='{po}'""")
sql(f"""INSERT INTO erp_local_stock_movement(
id,tenant_id,warehouse_id,product_sku_id,purchase_receipt_id,
movement_type,quantity,actor_user_id)
SELECT gen_random_uuid(),'{tenant}'::uuid,'{warehouse}'::uuid,
 l.product_sku_id,r.id,'PURCHASE_RECEIPT',1,'{actor}'::uuid
FROM erp_purchase_receipt r JOIN erp_purchase_order_line l
 ON l.tenant_id=r.tenant_id AND l.id=r.purchase_order_line_id
WHERE r.tenant_id='{tenant}' AND r.purchase_order_id='{po}'""")
sql(f"""INSERT INTO erp_local_purchase_plan(
id,tenant_id,request_key,warehouse_id,supplier_id,product_sku_id,
source_movement_id,policy_updated_at,rule_updated_at,
on_hand_quantity,reorder_point,target_stock,suggested_quantity,
minimum_order_quantity,pack_multiple,lead_time_days,ordered_quantity,
currency_code,unit_cost,estimated_arrival_date,created_by)
SELECT gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),'{warehouse}'::uuid,
 '{supplier}'::uuid,m.product_sku_id,m.id,p.updated_at,t.updated_at,
 1,5,20,19,1,1,14,19,'USD',1.00,current_date+14,'{actor}'::uuid
FROM erp_local_stock_movement m
JOIN erp_local_stock_policy p ON p.tenant_id=m.tenant_id
 AND p.warehouse_id=m.warehouse_id AND p.product_sku_id=m.product_sku_id
JOIN erp_supplier_sku_rule t ON t.tenant_id=m.tenant_id
 AND t.supplier_id='{supplier}' AND t.product_sku_id=m.product_sku_id
WHERE m.tenant_id='{tenant}' AND m.warehouse_id='{warehouse}'
AND m.purchase_receipt_id IN (
  SELECT id FROM erp_purchase_receipt WHERE tenant_id='{tenant}'
  AND purchase_order_id='{po}')""")
plan_base=f"/api/v1/purchasing/planning/warehouses/{warehouse}/plans"
plans,pages=traverse(plan_base+"/page")
assert len(plans)==125 and pages==3,(len(plans),pages)
assert len({p["id"] for p in plans})==125
assert all(p["status"]=="PROPOSED" and p["purchaseOrderId"] is None for p in plans)
assert len(call(plan_base))==100
call(f"/api/v1/purchasing/planning/warehouses/{other}/plans/page?"+
     urlencode({"cursor":get(plan_base+"/page")["nextCursor"]}),expected=400)

# 320 actual (synthetic) LOCAL receipt batches linked to the same local PO.
# A distinct line allows 226-unit batch parts without truncation.
batch_sku=sql(f"""SELECT id FROM product_sku WHERE tenant_id='{tenant}'
 AND internal_sku='V92-PAGED-512'""")
batch_line=str(uuid4())
sql(f"""INSERT INTO erp_purchase_order_line(
id,tenant_id,purchase_order_id,product_sku_id,ordered_quantity,unit_cost)
VALUES('{batch_line}','{tenant}','{po}','{batch_sku}',1000,1.00)""")
sql(f"""WITH sample AS (
 SELECT gen_random_uuid() AS id,i,
 CASE WHEN i=1 THEN 226 ELSE 1 END AS quantity
 FROM generate_series(1,320) AS i
), new_receipts AS (
 INSERT INTO erp_purchase_receipt(
 id,tenant_id,purchase_order_id,purchase_order_line_id,quantity,received_by,warehouse_id)
 SELECT id,'{tenant}'::uuid,'{po}'::uuid,'{batch_line}'::uuid,quantity,
   '{actor}'::uuid,'{warehouse}'::uuid FROM sample
 RETURNING id,quantity
), numbered AS (
 SELECT id,quantity,row_number() OVER(ORDER BY id) AS n FROM new_receipts
)
INSERT INTO erp_receipt_quality_batch(
tenant_id,receipt_id,warehouse_id,product_sku_id,received_quantity,lot_code,received_by)
SELECT '{tenant}'::uuid,id,'{warehouse}'::uuid,'{batch_sku}'::uuid,quantity,
 'V92-BATCH-'||lpad(n::text,3,'0'),'{actor}'::uuid FROM numbered""")
batch_base=f"/api/v1/purchasing/receipt-batches/orders/{po}"
batches,pages=traverse(batch_base+"/page")
assert len(batches)==320 and pages==7,(len(batches),pages)
assert len({b["receiptId"] for b in batches})==320
assert len(call(batch_base))==300
selected=next(b["receiptId"] for b in batches if b["receivedQuantity"]==226)
assert get(batch_base+"/page",limit=50)["hasMore"]
call(f"/api/v1/purchasing/receipt-batches/orders/{other}/page?"+
     urlencode({"cursor":get(batch_base+"/page")["nextCursor"]}),expected=400)

sql(f"""INSERT INTO erp_receipt_lot_piece(
id,tenant_id,receipt_id,warehouse_id,product_sku_id,bin_id,
piece_code,quantity,created_by)
SELECT gen_random_uuid(),'{tenant}'::uuid,'{selected}'::uuid,
 '{warehouse}'::uuid,'{batch_sku}'::uuid,'{rack}'::uuid,
 'V92-PIECE-'||lpad(i::text,3,'0'),
 CASE WHEN i=225 THEN 2 ELSE 1 END,'{actor}'::uuid
FROM generate_series(1,225) AS i""")
pieces_base=f"/api/v1/purchasing/lot-locations/receipts/{selected}/pieces"
pieces,pages=traverse(pieces_base+"/page")
assert len(pieces)==225 and pages==5
assert len({p["id"] for p in pieces})==225
assert sum(p["quantity"] for p in pieces)==226
p0=get(pieces_base+"/page",limit=50)
assert p0["totalQuantity"]==226
assert len(call(pieces_base))==200
call(f"/api/v1/purchasing/lot-locations/receipts/{other}/pieces/page?"+
     urlencode({"cursor":p0["nextCursor"]}),expected=400)
source=next(p for p in pieces if p["pieceCode"]=="V92-PIECE-225")
assert source["quantity"]==2
ledger_before=int(sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'"""))
po_before=int(sql(f"""SELECT COUNT(*) FROM erp_purchase_order
 WHERE tenant_id='{tenant}'"""))
call(f"/api/v1/purchasing/lot-locations/receipts/{selected}/split","POST",{
    "requestKey":str(uuid4()),"pieceId":source["id"],"quantity":1,
    "pieceCode":"V92-SPLIT-EXTRA","destinationBinId":rack,
    "reason":"V92 disposable full-page carton count"})
pieces_after,pages=traverse(pieces_base+"/page")
assert len(pieces_after)==226 and sum(p["quantity"] for p in pieces_after)==226
assert get(pieces_base+"/page")["totalQuantity"]==226
assert int(sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'"""))==ledger_before
assert int(sql(f"""SELECT COUNT(*) FROM erp_purchase_order
 WHERE tenant_id='{tenant}'"""))==po_before
assert sql(f"""SELECT COUNT(*) FROM erp_receipt_lot_piece
 WHERE tenant_id='{tenant}' AND receipt_id='{selected}'""")=="226"
print("PASS V92 local purchasing PG18: >512 rules and recommendations; "
      "125 PROPOSED plans; 215 bins; 320 receipt batches; "
      "225->226 lot pieces with SQL full sum=226, no new stock/PO/FBA writes")
