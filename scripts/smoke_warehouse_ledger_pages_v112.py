#!/usr/bin/env python3
"""V112 disposable PostgreSQL 18 + HTTP smoke: 125 signed local ledger rows.

Runs only in full_suite milestone. No Amazon credentials or live remote writes.
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
    assert status==expected,(path,status,expected,payload[:800])
    if method=="GET" and "/page" in path and status==200:
        assert "no-store" in cache,(path,cache)
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
def get(path,**params):return request(path+"?"+urlencode(params))

base="/api/v1/purchasing/warehouse-ledger/page"
request(base+"?warehouseId="+str(uuid4()),expected=401)
csrf=request("/api/v1/auth/csrf")["token"]
request("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=request("/api/v1/auth/csrf")["token"]
me=request("/api/v1/auth/me")
assert me["isOwner"]
tenant,actor=me["activeTenantId"],me["userId"]

warehouse=request("/api/v1/purchasing/warehouses","POST",{
    "warehouseCode":"V112LEDGER01",
    "displayName":"V112 Disposable Ledger Main"},expected=201)["id"]
other=request("/api/v1/purchasing/warehouses","POST",{
    "warehouseCode":"V112LEDGER02",
    "displayName":"V112 Disposable Ledger Other"},expected=201)["id"]
sku=sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 VALUES(gen_random_uuid(),'{tenant}'::uuid,
  'V112-LEDGER-SKU','V112 disposable ledger SKU') RETURNING id""")

# 124 count adjustments plus one signed outbound; each COUNT's claimed
# expected balance matches preceding movements. Four adjacent entries share
# a timestamp so the UUID tie-breaker is exercised by the real database.
sql(f"""INSERT INTO erp_local_stock_operation(
 id,tenant_id,request_key,operation_type,source_warehouse_id,
 product_sku_id,requested_quantity,expected_on_hand,applied_delta,
 after_quantity,reason,created_by,created_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),'COUNT',
  '{warehouse}'::uuid,'{sku}'::uuid,i,i-1,1,i,
  'V112 count synthetic disposable','{actor}'::uuid,
  TIMESTAMPTZ '2026-11-08T12:00:00Z'+(i/4)*interval '1 second'
 FROM generate_series(1,124) AS i""")
sql(f"""INSERT INTO erp_local_stock_movement(
 id,tenant_id,warehouse_id,product_sku_id,operation_id,movement_type,
 quantity,actor_user_id,created_at)
 SELECT gen_random_uuid(),tenant_id,source_warehouse_id,product_sku_id,id,
  'COUNT_ADJUSTMENT',1,created_by,created_at
 FROM erp_local_stock_operation
 WHERE tenant_id='{tenant}' AND source_warehouse_id='{warehouse}'
 AND product_sku_id='{sku}' AND reason='V112 count synthetic disposable'""")
outbound=sql(f"""INSERT INTO erp_local_stock_operation(
 id,tenant_id,request_key,operation_type,source_warehouse_id,
 product_sku_id,requested_quantity,expected_on_hand,applied_delta,
 after_quantity,reason,created_by,created_at)
 VALUES(gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),'OUTBOUND',
 '{warehouse}'::uuid,'{sku}'::uuid,2,124,-2,122,
 'V112 signed outbound disposable','{actor}'::uuid,
 TIMESTAMPTZ '2026-11-08T12:02:00Z') RETURNING id""")
sql(f"""INSERT INTO erp_local_stock_movement(
 id,tenant_id,warehouse_id,product_sku_id,operation_id,movement_type,
 quantity,actor_user_id,created_at)
 VALUES(gen_random_uuid(),'{tenant}'::uuid,'{warehouse}'::uuid,
 '{sku}'::uuid,'{outbound}'::uuid,'OUTBOUND',-2,'{actor}'::uuid,
 TIMESTAMPTZ '2026-11-08T12:02:00Z')""")
assert sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}' AND warehouse_id='{warehouse}'
 AND product_sku_id='{sku}'""")=="125"
assert sql(f"""SELECT SUM(quantity) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}' AND warehouse_id='{warehouse}'
 AND product_sku_id='{sku}'""")=="122"

def page(**params):
    out=get(base,warehouseId=warehouse,limit=50,search="V112-LEDGER-SKU",**params)
    assert out["pageSize"]==50
    return out
first=page()
second=page(cursor=first["nextCursor"])
third=page(cursor=second["nextCursor"])
assert [len(p["items"]) for p in (first,second,third)]==[50,50,25]
assert first["hasMore"] and second["hasMore"] and not third["hasMore"]
assert third["nextCursor"] is None
rows=first["items"]+second["items"]+third["items"]
assert len({r["id"] for r in rows})==125
assert all(r["warehouseId"]==warehouse and r["internalSku"]=="V112-LEDGER-SKU"
           for r in rows)
assert rows[0]["movementType"]=="OUTBOUND" and rows[0]["quantity"]==-2
assert rows[0]["operationId"]==outbound
assert rows[0]["reason"]=="V112 signed outbound disposable"
assert sum(r["quantity"] for r in rows)==122
assert [(r["createdAt"],r["id"]) for r in rows]==sorted(
    ((r["createdAt"],r["id"]) for r in rows),reverse=True)
assert len(get(base,warehouseId=warehouse,limit=50,
    movementType="OUTBOUND",search="V112-LEDGER-SKU")["items"])==1
assert get(base,warehouseId=warehouse,limit=50,
    movementType="QC_RELEASE",search="V112-LEDGER-SKU")["items"]==[]
assert get(base,warehouseId=warehouse,limit=50,search="%")["items"]==[]
assert get(base,warehouseId=other,limit=50)["items"]==[]
assert len(request("/api/v1/purchasing/warehouse-ledger?warehouseId="+warehouse))<=100

def error(**params):
    request(base+"?"+urlencode({"warehouseId":warehouse,"limit":50,
        "search":"V112-LEDGER-SKU",**params}),expected=400)
error(limit=0)
error(limit=101)
error(cursor="not+base64")
error(movementType="INVALID")
error(search="X"*81)
error(cursor=first["nextCursor"],movementType="OUTBOUND")
error(cursor=first["nextCursor"],search="V112-SOMETHING-ELSE")
error(cursor=first["nextCursor"],warehouseId=other)
request(base+"?"+urlencode({"warehouseId":str(uuid4()),"limit":50}),expected=404)
token=first["nextCursor"]
parts=base64.urlsafe_b64decode(token+"="*((-len(token))%4)).decode().split("\x1f")
assert len(parts)==7 and parts[1]==tenant and parts[2]==warehouse
parts[1]=str(uuid4())
error(cursor=base64.urlsafe_b64encode("\x1f".join(parts).encode()).decode().rstrip("="))
print("PASS V112: 125 signed local warehouse movements (50+50+25), same-time UUID,"
      " full server filters, scope rejection, no-store; disposable PostgreSQL only")
