#!/usr/bin/env python3
"""V87 PG18 real-HTTP isolated Listing page test, only disposable mock DB.
App OAuth/SP-API disabled. Run after V86 mock fixture, never on production.
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
def sql(q):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    p=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1","-h","127.0.0.1",
      "-U","merchivra","-d","merchivra","-Atc",q],
      capture_output=True,text=True,env=env,check=False)
    assert p.returncode==0,p.stderr[-1200:]
    return p.stdout.strip()
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method=="POST" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,method=method,
           data=data,headers=headers),timeout=35) as r:
            status,payload=r.status,r.read()
            cache=r.headers.get("Cache-Control","")
    except HTTPError as err:
        status,payload=err.code,err.read();cache=""
    assert status==expected,(method,path,status,expected,payload[:850])
    if status==200:assert "no-store" in cache,(path,cache)
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
def get(base,**kwargs):
    return call(base+"?"+urlencode(kwargs))

foreign=str(uuid4())
call(f"/api/v1/seller-marketplaces/{foreign}/listings/page",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
tenant=call("/api/v1/auth/me")["activeTenantId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp
base=f"/api/v1/seller-marketplaces/{mp}/listings/page"
call(f"/api/v1/seller-marketplaces/{foreign}/listings/page",expected=404)
for invalid in ({"limit":0},{"limit":101},{"field":"COST"},{"status":"ANY"},
                {"search":"X"*81},{"cursor":"invalid!"}):
    call(base+"?"+urlencode(invalid),expected=400)
call(base+"?search=V87%0AQUERY",expected=400)
assert sql("""SELECT count(*) FROM pg_indexes
 WHERE tablename='marketplace_listing'
 AND indexname IN ('marketplace_listing_v79_sku_prefix',
                   'marketplace_listing_v79_asin_prefix')""")=="2"

# 64 fixtures; 3 without ASIN; active, conflicted and never-mapped statuses.
# Use the existing marketplace without changing any original source/PO.
sql(f"""INSERT INTO marketplace_listing(
 id,tenant_id,seller_marketplace_id,seller_sku,asin,fnsku,
 mapping_status,source_updated_at,data_origin)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{mp}'::uuid,
 'V87-PAGED-'||lpad(i::text,3,'0'),
 CASE WHEN i>61 THEN NULL ELSE 'B0V87'||lpad(i::text,3,'0') END,
 NULL,
 CASE WHEN i=1 THEN 'ACTIVE' WHEN i IN (2,58,64) THEN 'CONFLICT'
      ELSE 'UNMAPPED' END,
 timestamptz '2026-11-03T00:00:00Z'-(i||' minutes')::interval,'MOCK'
 FROM generate_series(1,64) AS i
 ON CONFLICT (tenant_id,seller_marketplace_id,seller_sku) DO NOTHING""")
assert sql(f"""SELECT count(*) FROM marketplace_listing
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'
 AND seller_sku LIKE 'V87-PAGED-%'""")=="64"
sku=sql(f"SELECT id FROM product_sku WHERE tenant_id='{tenant}' ORDER BY id LIMIT 1")
assert sku,"V18-V86 fixtures must include one tenant-owned internal SKU"
internal=sql(f"SELECT internal_sku FROM product_sku WHERE tenant_id='{tenant}' AND id='{sku}'")
listing1=sql(f"""SELECT id FROM marketplace_listing WHERE tenant_id='{tenant}'
 AND seller_marketplace_id='{mp}' AND seller_sku='V87-PAGED-001'""")
listing2=sql(f"""SELECT id FROM marketplace_listing WHERE tenant_id='{tenant}'
 AND seller_marketplace_id='{mp}' AND seller_sku='V87-PAGED-002'""")
sql(f"""INSERT INTO listing_sku_mapping(
 id,tenant_id,listing_id,product_sku_id,status,valid_from,valid_to,mapping_version)
 VALUES(gen_random_uuid(),'{tenant}'::uuid,'{listing1}'::uuid,'{sku}'::uuid,
 'SUPERSEDED',timestamptz '2026-10-01T00:00:00Z',
 timestamptz '2026-10-02T00:00:00Z',1),
 (gen_random_uuid(),'{tenant}'::uuid,'{listing1}'::uuid,'{sku}'::uuid,
 'ACTIVE',timestamptz '2026-10-02T00:00:00Z',NULL,2),
 (gen_random_uuid(),'{tenant}'::uuid,'{listing2}'::uuid,'{sku}'::uuid,
 'CONFLICT',timestamptz '2026-11-01T00:00:00Z',NULL,3)""")
fifty=get(base,limit=50,search="v87-paged-",field="SELLER_SKU",status="ALL")
assert fifty["field"]=="SELLER_SKU" and fifty["searchPrefix"]=="v87-paged-"
assert fifty["mappingStatus"]=="ALL" and fifty["pageSize"]==50
assert fifty["hasMore"] and fifty["nextCursor"] and len(fifty["items"])==50
later=get(base,limit=50,search="V87-PAGED-",field="SELLER_SKU",
          status="ALL",cursor=fifty["nextCursor"])
assert len(later["items"])==14 and not later["hasMore"] and later["nextCursor"] is None
all_items=fifty["items"]+later["items"]
assert len({x["id"] for x in all_items})==64
assert [x["sellerSku"] for x in all_items[:3]]==[
 "V87-PAGED-001","V87-PAGED-002","V87-PAGED-003"]
assert all(x["dataOrigin"]=="MOCK" and x["sourceUpdatedAt"] for x in all_items)
active=all_items[0]
assert active["mappingStatus"]=="ACTIVE"
assert active["internalSku"]==internal
assert active["activeMappingVersion"]==2 and active["latestMappingVersion"]==2
conflict=all_items[1]
assert conflict["mappingStatus"]=="CONFLICT"
assert conflict["latestMappingVersion"]==3
assert conflict["activeMappingVersion"] is None and conflict["internalSku"] is None
assert all_items[2]["latestMappingVersion"] is None
assert all_items[2]["activeMappingVersion"] is None

# ASIN mode must query all stored records, not only the original 50.
asin_first=get(base,field="ASIN",search="b0v87",limit=50)
assert len(asin_first["items"])==50 and asin_first["hasMore"]
asin_last=get(base,field="ASIN",search="B0V87",limit=50,
              cursor=asin_first["nextCursor"])
assert len(asin_last["items"])==11 and not asin_last["hasMore"]
assert len({x["id"] for x in asin_first["items"]+asin_last["items"]})==61
assert not any(x["asin"] is None for x in asin_first["items"]+asin_last["items"])
# Status-only search over all 64 must not leak mappings from inactive histories.
conflicts=get(base,field="SELLER_SKU",search="v87-paged-",status="CONFLICT")
assert {x["sellerSku"] for x in conflicts["items"]}=={
 "V87-PAGED-002","V87-PAGED-058","V87-PAGED-064"}
assert len(get(base,field="SELLER_SKU",search="v87-paged-",
               status="ACTIVE")["items"])==1
unmapped_first=get(base,field="SELLER_SKU",search="v87-paged-",
                   status="UNMAPPED",limit=50)
assert len(unmapped_first["items"])==50 and unmapped_first["hasMore"]
unmapped_last=get(base,field="SELLER_SKU",search="v87-paged-",
                  status="UNMAPPED",limit=50,
                  cursor=unmapped_first["nextCursor"])
assert len(unmapped_last["items"])==10 and not unmapped_last["hasMore"]
assert all(x["mappingStatus"]=="UNMAPPED"
           for x in unmapped_first["items"]+unmapped_last["items"])
# Scope and filter changes after first page invalidate the cursor.
for changes in [
 {"field":"ASIN"},{"search":"V87-PAGED-0"},{"status":"CONFLICT"}]:
    opts={"field":"SELLER_SKU","search":"V87-PAGED-","status":"ALL",
          "cursor":fifty["nextCursor"],**changes}
    call(base+"?"+urlencode(opts),expected=400)
call(f"/api/v1/seller-marketplaces/{foreign}/listings/page?"+
     urlencode({"search":"v87-paged-","cursor":fifty["nextCursor"]}),expected=404)

# Literal LIKE special characters, including escape char !, never wildcard.
specials=["V87%LITERAL","V87_UNDERSCORE","V87!BANG",
          "V87-NL\nA","V87-NL\nB"]
for x in specials:
    # The fixtures below are generated internally, not external customer data.
    escaped=x.replace("'","''")
    sql(f"""INSERT INTO marketplace_listing(
      id,tenant_id,seller_marketplace_id,seller_sku,mapping_status,data_origin)
      VALUES(gen_random_uuid(),'{tenant}'::uuid,'{mp}'::uuid,
       '{escaped}','UNMAPPED','MOCK')
      ON CONFLICT(tenant_id,seller_marketplace_id,seller_sku) DO NOTHING""")
for prefix,expected in [("V87%LITERAL","V87%LITERAL"),
                         ("V87_UNDERSCORE","V87_UNDERSCORE"),
                         ("V87!BANG","V87!BANG")]:
    res=get(base,search=prefix)
    assert len(res["items"])==1 and res["items"][0]["sellerSku"]==expected
assert not get(base,search="V87%PAGED")["items"]
# Embedded newline in a persisted SKU must not corrupt the opaque cursor.
lf_first=get(base,search="V87-NL",limit=1)
assert lf_first["hasMore"] and len(lf_first["items"])==1
lf_last=get(base,search="V87-NL",limit=1,cursor=lf_first["nextCursor"])
assert len(lf_last["items"])==1 and not lf_last["hasMore"]
assert {x["sellerSku"] for x in lf_first["items"]+lf_last["items"]}=={
 "V87-NL\nA","V87-NL\nB"}

# Existing read contract and local inventory remain intact.
old=call(f"/api/v1/seller-marketplaces/{mp}/listings?limit=50")
assert isinstance(old,list) and len(old)<=50
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="0"
print("PASS V87: PG18 64 listing page split, ASIN/CONFLICT/ACTIVE search, exact mapping versions, CSRF Session 401/404, literal wildcard, newline-safe cursor, no Amazon writes")
