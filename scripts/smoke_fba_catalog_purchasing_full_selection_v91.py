#!/usr/bin/env python3
"""V91 PG18 cross-module SKU choice >100, local FBA shipment SKU >50.
Disposable localhost Mock only. Never touches real Amazon/Ads credentials.
"""
import http.cookiejar,json,os,subprocess
from urllib.request import Request,HTTPCookieProcessor,build_opener
from urllib.error import HTTPError
from urllib.parse import urlencode
from uuid import uuid4

assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"

http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=""
def sql(q):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    p=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
       "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",q],
       text=True,capture_output=True,env=env,check=False)
    assert p.returncode==0,p.stderr[-1800:]
    return p.stdout.strip()
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,method=method,
                  headers=headers,data=data),timeout=35) as r:
            status,raw=r.status,r.read()
    except HTTPError as exc:
        status,raw=exc.code,exc.read()
    assert status==expected,(method,path,status,expected,raw[:900])
    try:return json.loads(raw)
    except (ValueError,TypeError):return raw
def get(path,**kw):return call(path+"?"+urlencode(kw))
def post(path,body,expected=200):return call(path,"POST",body,expected)

call("/api/v1/catalog/skus?limit=100",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me")
assert me["isOwner"]
tenant=me["activeTenantId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp
site=next(m for m in call("/api/v1/seller-marketplaces") if m["id"]==mp)
currency=site["currency"]
listing=sql(f"""SELECT id FROM marketplace_listing
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'
 AND seller_sku='V87-PAGED-064'""")
assert listing
before_stock=int(sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'"""))
before_ledger=int(sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'"""))

# Internal catalog has a real bounded prefix/after query. Seed 130 distinct
# ACTIVE product SKUs in isolated PG18, not through a production import.
sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 SELECT gen_random_uuid(),'{tenant}'::uuid,
 'V91-INTERNAL-'||lpad(i::text,3,'0'),
 'V91 fictional SKU '||i FROM generate_series(1,130) AS i""")
base="/api/v1/catalog/skus"
first=get(base,prefix="v91-internal-",limit=100)
assert len(first)==100 and first[0]["internalSku"]=="V91-INTERNAL-001"
assert first[-1]["internalSku"]=="V91-INTERNAL-100"
assert not any(row["internalSku"]=="V91-INTERNAL-130" for row in first)
second=get(base,prefix="V91-INTERNAL-",limit=100,after=first[-1]["internalSku"])
assert len(second)==30 and second[-1]["internalSku"]=="V91-INTERNAL-130"
assert len({row["id"] for row in first+second})==130
assert all(row["status"]=="ACTIVE" for row in first+second)
latest=second[-1]
sku_id=latest["id"]
assert get(base,prefix="V91-INTERNAL-130",limit=100)==[latest]
assert get(base,prefix="V91-INTERNAL-%",limit=100)==[]
assert get(base,prefix="V91-INTERNAL-",limit=100,
           after="V91-INTERNAL-130")==[]
call(base+"?limit=101",expected=400)
call(base+"?after=bad%25",expected=400)

# Cross-module mapping of the 130th (not first 100) SKU to a 64th (not
# first 50) Marketplace Listing. Status must become ACTIVE with a REAL
# persisted mapping_version, no guessed mapping from SKU name similarity.
mapped=post(f"/api/v1/catalog/listings/{listing}/map",{
 "productSkuId":sku_id})
assert mapped["listingId"]==listing and mapped["productSkuId"]==sku_id
assert mapped["version"]>=1
actual=get(f"/api/v1/seller-marketplaces/{mp}/listings/page",
 search="V87-PAGED-064",field="SELLER_SKU",status="ACTIVE")
assert len(actual["items"])==1
row=actual["items"][0]
assert row["internalSku"]=="V91-INTERNAL-130"
assert row["activeMappingVersion"]==mapped["version"]
assert row["latestMappingVersion"]==mapped["version"]

# Historical original-currency costs should be append-only and scoped to
# the 130th internal SKU, without manufacturing net profit or settlement.
cost=post(f"/api/v1/catalog/skus/{sku_id}/costs",{
 "unitCost":"2.250000","currency":currency,
 "effectiveFrom":"2026-11-04"},201)
assert cost["productSkuId"]==sku_id and cost["currency"]==currency
assert cost["unitCost"]=="2.250000"
history=call(f"/api/v1/catalog/skus/{sku_id}/costs")
assert len(history)==1 and history[0]["id"]==cost["id"]

# Local FBA DRAFT permits any Seller SKU in the authorized Marketplace,
# including 64th SKU, but is NOT remote inbound or sellable FBA stock.
first_listing=get(f"/api/v1/seller-marketplaces/{mp}/listings/page",
 field="SELLER_SKU",search="V87-PAGED-",status="ALL",limit=50)
last_listing=get(f"/api/v1/seller-marketplaces/{mp}/listings/page",
 field="SELLER_SKU",search="V87-PAGED-",status="ALL",limit=50,
 cursor=first_listing["nextCursor"])
assert len(first_listing["items"])==50 and len(last_listing["items"])==14
assert "V87-PAGED-064" not in {r["sellerSku"] for r in first_listing["items"]}
assert "V87-PAGED-064" in {r["sellerSku"] for r in last_listing["items"]}
fba=f"/api/v1/fba/marketplaces/{mp}/shipments"
fake=post(fba,{"requestKey":str(uuid4()),"reference":"V91-LAST-LISTING-LOCAL",
 "origin":"MOCK","lines":[{"sellerSku":"V87-PAGED-064","quantity":4}]})
assert fake["shipment"]["state"]=="DRAFT"
assert fake["shipment"]["origin"]=="MOCK"
assert fake["amazonVerified"] is False and fake["fbaStockCredited"] is False
assert len(fake["lines"])==1
assert fake["lines"][0]["sellerSku"]=="V87-PAGED-064"
assert fake["lines"][0]["plannedQuantity"]==4
assert fake["lines"][0]["estimatedInTransit"] is None
assert call(fba+"/"+fake["shipment"]["id"])["shipment"]["state"]=="DRAFT"
# Same tenant but wrong Marketplace cannot silently accept this Listing:
other_mp=sql(f"""SELECT id FROM seller_marketplace
 WHERE tenant_id='{tenant}' AND id<>'{mp}'
 ORDER BY id LIMIT 1""")
assert other_mp
post(f"/api/v1/fba/marketplaces/{other_mp}/shipments",{
 "requestKey":str(uuid4()),"reference":"V91-FOREIGN-LISTING-REJECT",
 "origin":"MOCK","lines":[{"sellerSku":"V87-PAGED-064","quantity":1}]},400)

# Purchase with 130th internal SKU must also flow through the existing
# Owner-only purchasing write API, remain DRAFT (not auto-approved), and
# must not implicitly touch local warehouse/FBA available stock.
supplier=post("/api/v1/purchasing/suppliers",{
 "supplierCode":"V91SUPPLIER","displayName":"V91 Fictional Supplier"},201)
po=post("/api/v1/purchasing/orders",{
 "supplierId":supplier["id"],"currency":currency,
 "notes":"V91 mock 130th SKU cross-module acceptance",
 "lines":[{"productSkuId":sku_id,"quantity":6,"unitCost":"2.250000"}]},201)
assert po["order"]["status"]=="DRAFT"
assert po["lines"][0]["productSkuId"]==sku_id
assert po["lines"][0]["orderedQuantity"]==6
assert po["lines"][0]["receivedQuantity"]==0
assert sql(f"""SELECT status FROM erp_purchase_order
 WHERE tenant_id='{tenant}' AND id='{po['order']['id']}'""")=="DRAFT"
assert int(sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'"""))==before_stock
assert int(sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'"""))==before_ledger

print("PASS V91: 130 active internal SKUs 100+30 prefix/after;"
 " late SKU mapping/version & original-currency cost;"
 " 64th Listing FBA local DRAFT (cross-Marketplace denied);"
 " 130th internal SKU local PO DRAFT, no warehouse/FBA/Amazon writes")
