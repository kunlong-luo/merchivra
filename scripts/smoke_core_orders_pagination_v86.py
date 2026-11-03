#!/usr/bin/env python3
"""V86 P0: disposable PG18 HTTP full-list order keyset/prefix search.
Run only after V76..V85 mock fixture tests, NEVER against production sellers.
"""
import http.cookiejar,json,os,subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode,quote
from urllib.request import Request,HTTPCookieProcessor,build_opener
from uuid import uuid4
assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=""
def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    r=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1","-h","127.0.0.1",
     "-U","merchivra","-d","merchivra","-Atc",statement],
     capture_output=True,text=True,env=env,check=False)
    assert r.returncode==0,r.stderr[-1200:]
    return r.stdout.strip()
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method=="POST" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,method=method,
                 headers=headers,data=data),timeout=30) as r:
            status,payload=r.status,r.read()
            cache_control=r.headers.get("Cache-Control","")
    except HTTPError as exc:
        status,payload=exc.code,exc.read();cache_control=""
    assert status==expected,(method,path,status,expected,payload[:900])
    if status==200:assert "no-store" in cache_control,(path,cache_control)
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
foreign=str(uuid4())
call(f"/api/v1/seller-marketplaces/{foreign}/orders/page",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
tenant=call("/api/v1/auth/me")["activeTenantId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp
base=f"/api/v1/seller-marketplaces/{mp}/orders/page"
call(f"/api/v1/seller-marketplaces/{foreign}/orders/page",expected=404)
call(base+"?limit=101",expected=400)
call(base+"?limit=0",expected=400)
call(base+"?search="+quote("Z"*81),expected=400)
call(base+"?search=V86%0APAGED",expected=400)
call(base+"?cursor=not-a-real-cursor",expected=400)
# Only additive, fictitious, deterministic fixture order identifiers. Missing
# timestamps appear after observed timestamps and must support cursor paging.
sql(f"""INSERT INTO sales_order(
 id,tenant_id,seller_marketplace_id,external_order_id,
 fulfillment_status_raw,refund_status,financial_status,
 source_updated_at,data_origin,items_observation)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{mp}'::uuid,
   'V86-PAGED-'||lpad(i::text,3,'0'),
   'SHIPPED','NONE','NOT_OBSERVED',
   CASE WHEN i>61 THEN NULL ELSE
        timestamptz '2026-11-01T00:00:00Z'-(i||' minutes')::interval END,
   'MOCK','NOT_OBSERVED'
 FROM generate_series(1,64) AS i
 ON CONFLICT(tenant_id,seller_marketplace_id,external_order_id) DO NOTHING""")
sql(f"""INSERT INTO sales_order(
 id,tenant_id,seller_marketplace_id,external_order_id,
 fulfillment_status_raw,refund_status,financial_status,
 source_updated_at,data_origin,items_observation)
 VALUES(gen_random_uuid(),'{tenant}'::uuid,'{mp}'::uuid,
 'V86%LITERAL', 'PENDING','UNKNOWN','NOT_OBSERVED',NULL,'MOCK','NOT_OBSERVED')
 ON CONFLICT(tenant_id,seller_marketplace_id,external_order_id) DO NOTHING""")
assert sql(f"""SELECT count(*) FROM sales_order WHERE tenant_id='{tenant}'
 AND seller_marketplace_id='{mp}' AND external_order_id LIKE 'V86-PAGED-%'""")=="64"
first=call(base+"?limit=50&search=v86-paged-")
assert first["pageSize"]==50 and first["searchPrefix"]=="v86-paged-"
assert first["hasMore"] and first["nextCursor"] and len(first["items"])==50
assert all(x["dataOrigin"]=="MOCK" and x["itemsObservation"]=="NOT_OBSERVED"
           for x in first["items"])
assert all(x["externalOrderId"].startswith("V86-PAGED-") for x in first["items"])
second=call(base+"?"+urlencode({
 "limit":"50","search":"V86-PAGED-","cursor":first["nextCursor"]}))
assert len(second["items"])==14 and not second["hasMore"] and second["nextCursor"] is None
all_items=first["items"]+second["items"]
assert len({x["id"] for x in all_items})==64
assert [x["externalOrderId"] for x in all_items[:4]]==[
 "V86-PAGED-001","V86-PAGED-002","V86-PAGED-003","V86-PAGED-004"]
assert sum(x["sourceUpdatedAt"] is None for x in all_items)==3
assert all(x["sourceUpdatedAt"] is None for x in all_items[-3:])
assert all_items[-4]["sourceUpdatedAt"] is not None
call(base+"?"+urlencode({"search":"OTHER","cursor":first["nextCursor"]}),expected=400)
call(f"/api/v1/seller-marketplaces/{foreign}/orders/page?"+urlencode({
 "search":"v86-paged-","cursor":first["nextCursor"]}),expected=404)
# Cursor through NULL timestamp rows with no duplicates/missing records.
nulls=[]
cursor=None
for i in range(4):
    path=base+"?"+urlencode({
     "limit":"1","search":"V86-PAGED-06",
     **({"cursor":cursor} if cursor else {})})
    response=call(path)
    nulls.extend(response["items"])
    cursor=response["nextCursor"]
    if not cursor:break
assert len(nulls)==4 and len({x["id"] for x in nulls})==4
# Search filter is a literal prefix, never a SQL wildcard.
literal=call(base+"?search=V86%25LITERAL")
assert len(literal["items"])==1 and literal["items"][0]["externalOrderId"]=="V86%LITERAL"
assert not literal["hasMore"]
assert not call(base+"?search=V86%5FPAGED%5F")["items"]
assert len(call(base+"?search=V86-PAGED-099")["items"])==0
# Retain existing role-restricted order detail contract (no buyer PII).
one=all_items[0]
details=call(f"/api/v1/seller-marketplaces/{mp}/orders/{one['id']}/items")
assert details==[]
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="0"
print("PASS V86: PG18 64 orders across 50-row boundary, stable UUID/date+NULL cursor, literal prefix, 401/404/400, no PII/Amazon writes")
