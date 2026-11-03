#!/usr/bin/env python3
"""V88 FBA latest-SKU, full-store search and quality/provenance HTTP test.
Requires disposable PostgreSQL18 + V76-V87 fixtures, never production or Amazon.
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
assert os.getenv("MERCHIVRA_SPAPI_ENABLED") == "false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
assert os.getenv("MERCHIVRA_DEMO_ENABLED") == "true"

client = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = ""
def sql(command):
    env = {**os.environ, "PGPASSWORD":os.environ["DB_PASSWORD"]}
    result = subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",command],
        text=True,capture_output=True,env=env,check=False)
    assert result.returncode == 0, result.stderr[-1500:]
    return result.stdout.strip()

def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    payload=None
    if body is not None:
        payload=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
    if method == "POST" and csrf:
        headers["X-CSRF-TOKEN"]=csrf
    try:
        with client.open(Request("http://127.0.0.1:8080"+path,
                method=method,headers=headers,data=payload),timeout=35) as response:
            status,content=response.status,response.read()
            cache=response.headers.get("Cache-Control","")
    except HTTPError as exc:
        status,content=exc.code,exc.read()
        cache=""
    assert status==expected,(method,path,status,expected,content[:900])
    if status==200:
        assert "no-store" in cache,(path,cache)
    try:
        return json.loads(content)
    except (ValueError,TypeError):
        return content

def get(base,**kwargs):
    return call(base+"?"+urlencode(kwargs))

foreign=str(uuid4())
call(f"/api/v1/seller-marketplaces/{foreign}/inventory/fba/page",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]
},form=True)
tenant=call("/api/v1/auth/me")["activeTenantId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp,"V76 disposable Marketplace missing"
base=f"/api/v1/seller-marketplaces/{mp}/inventory/fba/page"
call(f"/api/v1/seller-marketplaces/{foreign}/inventory/fba/page",expected=404)
for invalid in ({"limit":0},{"limit":101},{"quality":"COMPLETE"},
                {"origin":"VERIFIED_BY_AMAZON"},{"search":"X"*81},
                {"cursor":"not-a-real-cursor"}):
    call(base+"?"+urlencode(invalid),expected=400)
call(base+"?search=V88%0AQUERY",expected=400)

assert sql("""SELECT count(*) FROM pg_indexes
 WHERE tablename='fba_inventory_observation'
   AND indexname IN ('fba_inventory_v80_latest_per_sku',
                     'fba_inventory_v80_lower_sku_prefix')""")=="2"
shipments_before=int(sql(f"""SELECT count(*) FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'"""))
audit_before=int(sql(f"""SELECT count(*) FROM fba_purchase_exception_audit
 WHERE tenant_id='{tenant}'"""))
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="0"

# One current source observation for each of 64 SKUs.
# The selected latest records include an observed zero, UNKNOWN null,
# STALE nonzero, and PARTIAL null reserved; source timestamps can be null.
sql(f"""INSERT INTO fba_inventory_observation(
 id,tenant_id,seller_marketplace_id,seller_sku_raw,
 source_observation_key,quality,fulfillable,reserved,observed_at,
 ingested_at,data_origin)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{mp}'::uuid,
   'V88-PAGED-'||lpad(i::text,3,'0'),
   'V88-PAGED-CURRENT-'||lpad(i::text,3,'0'),
   CASE WHEN i IN (2,64) THEN 'UNKNOWN'
        WHEN i=3 THEN 'STALE'
        WHEN i=4 THEN 'PARTIAL' ELSE 'OBSERVED' END,
   CASE WHEN i IN (2,64) THEN NULL WHEN i=1 THEN 0
        WHEN i=3 THEN 7 WHEN i=4 THEN 5 ELSE 20 END,
   CASE WHEN i IN (2,4,64) THEN NULL WHEN i=1 THEN 0
        WHEN i=3 THEN 2 ELSE 1 END,
   CASE WHEN i=64 THEN NULL
        ELSE timestamptz '2026-11-03T00:00:00Z'-(i||' minutes')::interval END,
   timestamptz '2026-11-03T01:00:00Z',
   CASE WHEN i=2 THEN 'UNVERIFIED' ELSE 'MOCK' END
 FROM generate_series(1,64) i""")
# Older observations are deliberately tempting: they must NOT get surfaced
# by quality/source filtering before latest-per-SKU selection.
sql(f"""INSERT INTO fba_inventory_observation(
 id,tenant_id,seller_marketplace_id,seller_sku_raw,
 source_observation_key,quality,fulfillable,reserved,observed_at,
 ingested_at,data_origin)
 VALUES
 (gen_random_uuid(),'{tenant}','{mp}','V88-PAGED-001',
  'V88-OLD-001','UNKNOWN',NULL,NULL,
  timestamptz '2026-10-01T00:00:00Z',now(),'MOCK'),
 (gen_random_uuid(),'{tenant}','{mp}','V88-PAGED-002',
  'V88-OLD-002','OBSERVED',45,1,
  timestamptz '2026-10-01T00:00:00Z',now(),'MOCK'),
 (gen_random_uuid(),'{tenant}','{mp}','V88-PAGED-003',
  'V88-OLD-003','OBSERVED',88,2,
  timestamptz '2026-10-01T00:00:00Z',now(),'MOCK'),
 (gen_random_uuid(),'{tenant}','{mp}','V88-PAGED-004',
  'V88-NO-TIME-004','UNKNOWN',NULL,NULL,
  NULL,now(),'MOCK'),
 (gen_random_uuid(),'{tenant}','{mp}','V88-PAGED-005',
  'V88-INGEST-TIE-005','PARTIAL',6,NULL,
  timestamptz '2026-11-02T23:55:00Z',
  timestamptz '2026-11-03T02:00:00Z','MOCK'),
 (gen_random_uuid(),'{tenant}','{mp}','V88-PAGED-064',
  'V88-OLDER-NULL-064','OBSERVED',99,8,
  NULL,timestamptz '2026-11-02T01:00:00Z','MOCK')""")
assert sql(f"""SELECT count(DISTINCT seller_sku_raw)
 FROM fba_inventory_observation WHERE tenant_id='{tenant}'
 AND seller_marketplace_id='{mp}'
 AND seller_sku_raw LIKE 'V88-PAGED-%'""")=="64"

first=get(base,limit=50,search="v88-paged-")
assert first["pageSize"]==50 and first["searchPrefix"]=="v88-paged-"
assert first["quality"]=="ALL" and first["origin"]=="ALL"
assert first["hasMore"] and first["nextCursor"] and len(first["items"])==50
later=get(base,limit=50,search="V88-PAGED-",cursor=first["nextCursor"])
assert len(later["items"])==14 and not later["hasMore"] and later["nextCursor"] is None
rows=first["items"]+later["items"]
assert len(rows)==64 and len({r["id"] for r in rows})==64
assert [r["sellerSku"] for r in rows[:3]]==[
 "V88-PAGED-001","V88-PAGED-002","V88-PAGED-003"]
assert all(r["ingestedAt"] and r["dataOrigin"] in ("MOCK","UNVERIFIED") for r in rows)

by_sku={r["sellerSku"]:r for r in rows}
zero=by_sku["V88-PAGED-001"]
assert zero["quality"]=="OBSERVED" and zero["fulfillable"]==0
assert zero["reserved"]==0 and zero["observedAt"] is not None
unknown=by_sku["V88-PAGED-002"]
assert unknown["quality"]=="UNKNOWN" and unknown["fulfillable"] is None
assert unknown["reserved"] is None and unknown["dataOrigin"]=="UNVERIFIED"
stale=by_sku["V88-PAGED-003"]
assert stale["quality"]=="STALE" and stale["fulfillable"]==7
partial=by_sku["V88-PAGED-004"]
assert partial["quality"]=="PARTIAL" and partial["reserved"] is None
tie=by_sku["V88-PAGED-005"]
assert tie["quality"]=="PARTIAL" and tie["fulfillable"]==6
no_time=by_sku["V88-PAGED-064"]
assert no_time["quality"]=="UNKNOWN" and no_time["observedAt"] is None
assert no_time["fulfillable"] is None and no_time["ingestedAt"] is not None

# Filters are AFTER DISTINCT ON. Older OBSERVED/UNKNOWN must never be
# included as 'latest' when the SKU has a newer contrary observation.
unknown_rows=get(base,search="V88-PAGED-",quality="UNKNOWN")["items"]
assert {r["sellerSku"] for r in unknown_rows}=={
 "V88-PAGED-002","V88-PAGED-064"}
assert all(r["fulfillable"] is None for r in unknown_rows)
assert {r["sellerSku"] for r in get(base,search="V88-PAGED-",
   quality="STALE")["items"]}=={"V88-PAGED-003"}
assert {r["sellerSku"] for r in get(base,search="V88-PAGED-",
   quality="PARTIAL")["items"]}=={"V88-PAGED-004","V88-PAGED-005"}
observed_first=get(base,search="V88-PAGED-",quality="OBSERVED",limit=50)
assert len(observed_first["items"])==50 and observed_first["hasMore"]
observed_last=get(base,search="V88-PAGED-",quality="OBSERVED",
                  limit=50,cursor=observed_first["nextCursor"])
assert len(observed_last["items"])==9 and not observed_last["hasMore"]
assert len({r["sellerSku"] for r in
            observed_first["items"]+observed_last["items"]})==59
assert all(r["quality"]=="OBSERVED" for r in
           observed_first["items"]+observed_last["items"])

unverified=get(base,search="V88-PAGED-",origin="UNVERIFIED")
assert {r["sellerSku"] for r in unverified["items"]}=={"V88-PAGED-002"}
assert not get(base,search="V88-PAGED-",quality="OBSERVED",
               origin="UNVERIFIED")["items"]
mock_first=get(base,search="V88-PAGED-",origin="MOCK",limit=50)
mock_last=get(base,search="V88-PAGED-",origin="MOCK",
              limit=50,cursor=mock_first["nextCursor"])
assert len(mock_first["items"])==50 and len(mock_last["items"])==13
assert not get(base,search="V88-PAGED-",origin="LIVE")["items"]

# Cursors cannot be reused for another quality/source/Marketplace/prefix.
for change in ({"quality":"UNKNOWN"},{"origin":"MOCK"},{"search":"V88-PAGED-0"}):
    args={"search":"V88-PAGED-","quality":"ALL","origin":"ALL",
          "cursor":first["nextCursor"],**change}
    call(base+"?"+urlencode(args),expected=400)
call(f"/api/v1/seller-marketplaces/{foreign}/inventory/fba/page?"+
     urlencode({"search":"V88-PAGED-","cursor":first["nextCursor"]}),expected=404)

# Percent, underscore and exclamation are literal prefix characters.
# Newline in a source SKU is allowed as stored evidence but not in a search
# parameter. Cursor may still roundtrip it via V87's UTF-8/base64 SKU component.
specials=["V88%LITERAL","V88_UNDERSCORE","V88!BANG",
          "V88-NL\nA","V88-NL\nB"]
for idx, sku in enumerate(specials):
    escaped=sku.replace("'","''")
    sql(f"""INSERT INTO fba_inventory_observation(
      id,tenant_id,seller_marketplace_id,seller_sku_raw,
      source_observation_key,quality,fulfillable,reserved,
      observed_at,ingested_at,data_origin)
      VALUES(gen_random_uuid(),'{tenant}','{mp}','{escaped}',
       'V88-EXTRA-{idx}','UNKNOWN',NULL,NULL,
       NULL,timestamptz '2026-11-03T03:00:00Z','MOCK')""")
for prefix in ("V88%LITERAL","V88_UNDERSCORE","V88!BANG"):
    got=get(base,search=prefix)
    assert len(got["items"])==1 and got["items"][0]["sellerSku"]==prefix
assert not get(base,search="V88%PAGED")["items"]
line_first=get(base,search="V88-NL",limit=1)
assert len(line_first["items"])==1 and line_first["hasMore"]
line_last=get(base,search="V88-NL",limit=1,
              cursor=line_first["nextCursor"])
assert len(line_last["items"])==1 and not line_last["hasMore"]
assert {r["sellerSku"] for r in
    line_first["items"]+line_last["items"]}=={"V88-NL\nA","V88-NL\nB"}

# Original endpoint remains a bounded, backward-compatible list.
old=call(f"/api/v1/seller-marketplaces/{mp}/inventory/fba?limit=50")
assert isinstance(old,list) and len(old)<=50
assert int(sql(f"""SELECT count(*) FROM fba_inbound_shipment WHERE tenant_id='{tenant}'
 AND seller_marketplace_id='{mp}'"""))==shipments_before
assert int(sql(f"""SELECT count(*) FROM fba_purchase_exception_audit
 WHERE tenant_id='{tenant}'"""))==audit_before
print("PASS V88: PG18 64 SKU 50+14; OBSERVED zero vs UNKNOWN NULL; STALE/PARTIAL;"
      " source time/ingest tie-break; latest-before-filter; origin, special SKU;"
      " scoped cursor 400/401/404; no Amazon, shipment or audit writes")
