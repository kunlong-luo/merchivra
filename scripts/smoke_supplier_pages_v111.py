#!/usr/bin/env python3
"""V111 PG18 HTTP smoke: full supplier directory and 201st+ local PO draft.
Disposable localhost MOCK database only. Runs in full milestone, not fast CI.
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

base="/api/v1/purchasing/suppliers/page"
request(base+"?limit=50",expected=401)
csrf=request("/api/v1/auth/csrf")["token"]
request("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=request("/api/v1/auth/csrf")["token"]
me=request("/api/v1/auth/me")
assert me["isOwner"]
tenant=me["activeTenantId"]

sql(f"""INSERT INTO erp_supplier(id,tenant_id,supplier_code,display_name)
SELECT gen_random_uuid(),'{tenant}'::uuid,'V111-SUP-'||lpad(i::text,4,'0'),
 'V111 disposable supplier '||i FROM generate_series(1,225) AS i""")
sql(f"""INSERT INTO erp_supplier(id,tenant_id,supplier_code,display_name,status)
 VALUES(gen_random_uuid(),'{tenant}'::uuid,
 'V111-ARCH-0001','V111 archived supplier','ARCHIVED')""")
assert sql(f"""SELECT count(*) FROM erp_supplier
 WHERE tenant_id='{tenant}' AND supplier_code LIKE 'V111-SUP-%'""")=="225"

def page(**kwargs):
    out=get(base,limit=50,status="ACTIVE",prefix="V111-SUP-",**kwargs)
    assert out["pageSize"]==50
    return out

pages=[]
cursor=None
for i in range(5):
    out=page(**({"cursor":cursor} if cursor else {}))
    pages.append(out)
    cursor=out["nextCursor"]
assert [len(p["items"]) for p in pages]==[50,50,50,50,25]
assert all(p["hasMore"] for p in pages[:4])
assert not pages[4]["hasMore"] and cursor is None
rows=[s for p in pages for s in p["items"]]
assert len({s["id"] for s in rows})==225
assert [s["code"] for s in rows]==sorted(s["code"] for s in rows)
assert rows[200]["code"]=="V111-SUP-0201"
assert all(s["status"]=="ACTIVE" for s in rows)
assert get(base,limit=50,status="ACTIVE",prefix="V111-SUP-0201")["items"]==[rows[200]]
assert get(base,limit=50,status="ARCHIVED",prefix="V111-ARCH-")["items"][0]["status"]=="ARCHIVED"
assert get(base,limit=50,status="ACTIVE",prefix="V111-ARCH-")["items"]==[]
assert get(base,limit=50,status="ALL",prefix="V111_SUP_")["items"]==[]
assert len(request("/api/v1/purchasing/suppliers"))<=200

def error(**kwargs):
    request(base+"?"+urlencode({"limit":50,"status":"ACTIVE",
        "prefix":"V111-SUP-",**kwargs}),expected=400)
error(limit=0)
error(limit=101)
error(cursor="not+base64")
error(status="DISABLED")
error(prefix="SUP%")
error(prefix="S"*61)
error(cursor=pages[0]["nextCursor"],status="ARCHIVED")
error(cursor=pages[0]["nextCursor"],prefix="V111-SUP-0201")
token=pages[0]["nextCursor"]
parts=base64.urlsafe_b64decode(token+"="*((-len(token))%4)).decode().split("\x1f")
assert len(parts)==6 and parts[1]==tenant
parts[1]=str(uuid4())
forged=base64.urlsafe_b64encode("\x1f".join(parts).encode()).decode().rstrip("=")
error(cursor=forged)

sku=sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 VALUES(gen_random_uuid(),'{tenant}'::uuid,'V111-LATE-PO-SKU',
 'V111 disposable purchase SKU') RETURNING id""")
po=request("/api/v1/purchasing/orders","POST",{
    "supplierId":rows[200]["id"],"currency":"USD",
    "notes":"V111 supplier beyond former 200-row hard cap",
    "lines":[{"productSkuId":sku,"quantity":2,"unitCost":"3.00"}]},201)
assert po["order"]["status"]=="DRAFT"
assert po["order"]["supplierId"]==rows[200]["id"]
assert po["lines"][0]["receivedQuantity"]==0
archived=get(base,limit=50,status="ARCHIVED",prefix="V111-ARCH-")["items"][0]
request("/api/v1/purchasing/orders","POST",{
    "supplierId":archived["id"],"currency":"USD",
    "lines":[{"productSkuId":sku,"quantity":1,"unitCost":"3.00"}]},expected=404)
assert sql(f"""SELECT status FROM erp_purchase_order
 WHERE tenant_id='{tenant}' AND id='{po['order']['id']}'""")=="DRAFT"
print("PASS V111: 225 suppliers 50+50+50+50+25, 201st supplier local PO DRAFT,"
      " ACTIVE/ARCHIVED and literal prefix, no-store, forged scope cursors")
