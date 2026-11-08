#!/usr/bin/env python3
"""V110 actual PostgreSQL 18 HTTP test: 125 locally created purchase orders,
server-side filtering, opaque cursors and tenant/scope negative cases.

Only disposable localhost PostgreSQL with all Amazon live switches OFF.
Never against shared or production databases.
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
    assert status==expected,(path,status,expected,payload[:1000])
    if method=="GET" and "/page" in path and status==200:
        assert "no-store" in cache,(path,cache)
    try:return json.loads(payload)
    except(ValueError,TypeError):return payload

def get(path,**params):return request(path+"?"+urlencode(params))

base="/api/v1/purchasing/orders/page"
request(base+"?limit=50",expected=401)
csrf=request("/api/v1/auth/csrf")["token"]
request("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=request("/api/v1/auth/csrf")["token"]
me=request("/api/v1/auth/me")
tenant,actor=me["activeTenantId"],me["userId"]
assert me["isOwner"]

supplier=request("/api/v1/purchasing/suppliers","POST",{
    "supplierCode":"V110PAGE","displayName":"V110 disposable local supplier"},201)["id"]
sql(f"""INSERT INTO erp_purchase_order (
    id,tenant_id,supplier_id,order_number,currency_code,created_by,updated_at)
SELECT gen_random_uuid(),'{tenant}'::uuid,'{supplier}'::uuid,
       'PO-V110-SYN-'||lpad(i::text,3,'0'),'USD','{actor}'::uuid,
       TIMESTAMPTZ '2026-11-08T00:00:00Z'
FROM generate_series(1,125) AS i""")

assert sql(f"""SELECT COUNT(*) FROM erp_purchase_order
  WHERE tenant_id='{tenant}' AND order_number LIKE 'PO-V110-SYN-%'""")=="125"

def page(**params):
    out=get(base,limit=50,search="PO-V110-SYN-",**params)
    assert out["pageSize"]==50
    assert isinstance(out["items"],list)
    return out

a=page()
b=page(cursor=a["nextCursor"])
c=page(cursor=b["nextCursor"])
assert list(map(lambda p:len(p["items"]),(a,b,c)))==[50,50,25]
assert a["hasMore"] and b["hasMore"] and not c["hasMore"]
assert c["nextCursor"] is None
rows=a["items"]+b["items"]+c["items"]
assert len({x["id"] for x in rows})==125
assert all(x["orderNumber"].startswith("PO-V110-SYN-") for x in rows)
assert all(x["status"]=="DRAFT" for x in rows)
assert [x["updatedAt"] for x in rows]==sorted(
    (x["updatedAt"] for x in rows),reverse=True)
# Verify the full-tenant filter, not on-page-only search.
assert len(get(base,limit=50,status="DRAFT",search="PO-V110-SYN-121")["items"])==1
assert get(base,limit=50,status="APPROVED",search="PO-V110-SYN-")["items"]==[]
assert len(request("/api/v1/purchasing/orders"))<=100  # legacy compatibility

def error(**params):
    request(base+"?"+urlencode({"limit":50,"search":"PO-V110-SYN-",**params}),
        expected=400)

error(limit=0)
error(limit=101)
error(cursor="not+base64")
error(status="INVALID")
error(search="PO%")
error(search="X"*81)
error(cursor=a["nextCursor"],status="APPROVED")
error(cursor=a["nextCursor"],search="PO-V110-SYN-121")

# A forged token from another tenant cannot be reused, even if it is valid Base64.
token=a["nextCursor"]
decoded=base64.urlsafe_b64decode(token+"="*((-len(token))%4)).decode().split("\x1f")
assert len(decoded)==6 and decoded[1]==tenant
decoded[1]=str(uuid4())
forged=base64.urlsafe_b64encode("\x1f".join(decoded).encode()).decode().rstrip("=")
error(cursor=forged)

assert sql(f"""SELECT COUNT(*) FROM erp_purchase_order
  WHERE tenant_id='{tenant}' AND order_number LIKE 'PO-V110-SYN-%'""")=="125"
print("PASS V110: 125 LOCAL purchase orders -> 50+50+25; server search, statuses,"
      " no-store, cursor binding, forbidden tenants/filters, legacy preserved")
