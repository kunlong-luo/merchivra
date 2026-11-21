#!/usr/bin/env python3
"""V114 disposable PG18 HTTP: 225 warehouse choices and 201st warehouse COUNT.

Only localhost PostgreSQL 18, disposable Mock data, Amazon LIVE all off.
Runs exclusively inside the full_suite milestone job.
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
    assert run.returncode==0,(query[:200],run.stderr[-900:])
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
    assert status==expected,(path,status,expected,payload[:800])
    if method=="GET" and ("/page" in path or "/item" in path) and status==200:
        assert "no-store" in cache,(path,cache)
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
def get(path,**params):return request(path+"?"+urlencode(params))

base="/api/v1/purchasing/warehouses/page"
item="/api/v1/purchasing/warehouses/item"
request(base+"?limit=50",expected=401)
csrf=request("/api/v1/auth/csrf")["token"]
request("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=request("/api/v1/auth/csrf")["token"]
me=request("/api/v1/auth/me")
tenant,actor=me["activeTenantId"],me["userId"]
assert me["isOwner"]
sql(f"""INSERT INTO erp_local_warehouse(
 id,tenant_id,warehouse_code,display_name,created_by)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'V114-WH-'||lpad(i::text,4,'0'),
  'V114 disposable warehouse '||i,'{actor}'::uuid
 FROM generate_series(1,225) AS i""")
assert sql(f"""SELECT count(*) FROM erp_local_warehouse
 WHERE tenant_id='{tenant}' AND warehouse_code LIKE 'V114-WH-%'""")=="225"
sql(f"""INSERT INTO erp_local_stock_bin(
 id,tenant_id,warehouse_id,bin_code,purpose,created_by)
 SELECT gen_random_uuid(),tenant_id,id,'QC-HOLD','QC_HOLD','{actor}'::uuid
 FROM erp_local_warehouse WHERE tenant_id='{tenant}'
 AND warehouse_code LIKE 'V114-WH-%'""")
def page(**params):
    out=get(base,limit=50,status="ACTIVE",prefix="V114-WH-",**params)
    assert out["pageSize"]==50
    return out

pages=[]
cursor=None
for i in range(5):
    p=page(**({"cursor":cursor} if cursor else {}))
    pages.append(p)
    cursor=p["nextCursor"]
assert [len(p["items"]) for p in pages]==[50,50,50,50,25]
assert all(p["hasMore"] for p in pages[:4])
assert not pages[-1]["hasMore"] and cursor is None
rows=[w for p in pages for w in p["items"]]
assert len({w["id"] for w in rows})==225
assert rows[200]["code"]=="V114-WH-0201"
assert rows==sorted(rows,key=lambda w:(w["code"],w["id"]))
assert all(w["status"]=="ACTIVE" for w in rows)
late=rows[200]
assert get(item,warehouseId=late["id"])==late
# V115: batch resolve historical labels (including the 201st warehouse)
# without per-row N+1 calls. Unknown/foreign IDs must not be fabricated.
ids=late["id"]+","+str(uuid4())
assert get("/api/v1/purchasing/warehouses/resolve",ids=ids)==[late]
assert get("/api/v1/purchasing/warehouses/resolve",ids=str(uuid4()))==[]
request("/api/v1/purchasing/warehouses/resolve?ids=",expected=400)
request("/api/v1/purchasing/warehouses/resolve?ids=bad-uuid",expected=400)
assert get(base,limit=50,status="ACTIVE",prefix="V114-WH-0201")["items"]==[late]
assert get(base,limit=50,status="ACTIVE",prefix="V114_WH_")["items"]==[]
assert len(request("/api/v1/purchasing/warehouses"))<=200

def error(**params):
    request(base+"?"+urlencode({"limit":50,"status":"ACTIVE",
        "prefix":"V114-WH-",**params}),expected=400)
error(limit=0)
error(limit=101)
error(status="HIDDEN")
error(prefix="WH%")
error(prefix="W"*61)
error(cursor="not+base64")
error(cursor=pages[0]["nextCursor"],prefix="V114-WH-0201")
error(cursor=pages[0]["nextCursor"],status="ARCHIVED")
token=pages[0]["nextCursor"]
parts=base64.urlsafe_b64decode(token+"="*((-len(token))%4)).decode().split("\x1f")
assert len(parts)==6 and parts[1]==tenant
parts[1]=str(uuid4())
error(cursor=base64.urlsafe_b64encode("\x1f".join(parts).encode()).decode().rstrip("="))
request(item+"?warehouseId="+str(uuid4()),expected=404)

sku=sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 VALUES(gen_random_uuid(),'{tenant}'::uuid,'V114-COUNT-SKU',
  'V114 disposable warehouse 201 count SKU') RETURNING id""")
observed=get("/api/v1/purchasing/warehouse-stock/item",
    warehouseId=late["id"],productSkuId=sku)
assert observed["onHandQuantity"]==0
count=request("/api/v1/purchasing/warehouse-operations","POST",{
    "requestKey":str(uuid4()),"type":"COUNT","warehouseId":late["id"],
    "destinationWarehouseId":None,"productSkuId":sku,"quantity":1,
    "expectedOnHand":0,"reason":"V114 warehouse beyond legacy 200 COUNT"},201)
assert count["warehouseId"]==late["id"] and count["afterQuantity"]==1
assert get("/api/v1/purchasing/warehouse-stock/item",
    warehouseId=late["id"],productSkuId=sku)["onHandQuantity"]==1
print("PASS V114: 225 local warehouses (50+50+50+50+25), tenant/status/prefix,"
      " forged cursors, 201st warehouse exact lookup + signed COUNT; PG18 mock only")
