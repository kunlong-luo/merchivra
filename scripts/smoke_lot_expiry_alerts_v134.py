#!/usr/bin/env python3
"""V134 true PG18 HTTP: all expiry alerts, date order, scopes and nonposting.

Runs only in the existing disposable A3 full_suite after V95 V132 V133.
Direct SQL fixtures use synthetic PENDING_QC batch positions, not actual
warehouse release or Amazon calls. Source rows are deleted after assertions.
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
assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES","false") == "false"

http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

def sql(query):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    p=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
        text=True,capture_output=True,env=env,check=False)
    assert p.returncode==0,(query[:230],p.stderr[-1200:])
    return p.stdout.strip()

def call(path,expected=200,method="GET",body=None,csrf=None):
    headers={"Accept":"application/json"}
    data=None
    if csrf:headers["X-CSRF-TOKEN"]=csrf
    if body is not None:
        data=urlencode(body).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded"
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,
            headers=headers,method=method,data=data),timeout=50) as response:
            status,raw,cache=response.status,response.read(),response.headers.get("Cache-Control","")
    except HTTPError as error:
        status,raw,cache=error.code,error.read(),""
    assert status==expected,(path,status,expected,raw[:700])
    if expected!=200:return None
    if "/expiry-alerts/page?" in path:assert "no-store" in cache,(path,cache)
    return json.loads(raw)

call(f"/api/v1/purchasing/lot-lifecycle/warehouses/{uuid4()}/expiry-alerts/page?limit=50",401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login",method="POST",body={
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},csrf=csrf)
me=call("/api/v1/auth/me")
assert me["isOwner"]
tenant=me["activeTenantId"]
source=sql(f"""SELECT warehouse_id,receipt_id,product_sku_id,bin_id
 FROM erp_local_lot_position WHERE tenant_id='{tenant}'
 AND reason='V95 synthetic nonposting evidence position' LIMIT 1""").split("|")
assert len(source)==4,source
warehouse,receipt,sku,bin_id=source
marker="V134 synthetic nonposting expiry fixture"
before_stock=sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")
inserted=sql(f"""WITH added AS (
 INSERT INTO erp_local_lot_position(
 id,tenant_id,request_key,receipt_id,warehouse_id,product_sku_id,
 bin_id,placed_quantity,remaining_quantity,expiry_date,reason,created_by)
 SELECT gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),'{receipt}'::uuid,
 '{warehouse}'::uuid,'{sku}'::uuid,'{bin_id}'::uuid,1,1,
 CASE WHEN i<=160 THEN CURRENT_DATE-3
      WHEN i<=325 THEN CURRENT_DATE+10
      ELSE CURRENT_DATE+90 END,
 '{marker}','{me["userId"]}'::uuid
 FROM generate_series(1,330) i RETURNING id
) SELECT count(*) FROM added""")
assert inserted=="330",inserted
original=sql(f"""SELECT id,CASE WHEN expiry_date<CURRENT_DATE THEN 'EXPIRED'
 ELSE 'NEAR_EXPIRY' END FROM erp_local_lot_position
 WHERE tenant_id='{tenant}' AND reason='{marker}'
 AND expiry_date<=CURRENT_DATE+30 ORDER BY expiry_date,id""").splitlines()
expected={v.split("|")[0]:v.split("|")[1] for v in original}
assert len(expected)==325
excluded=set(sql(f"""SELECT id FROM erp_local_lot_position
 WHERE tenant_id='{tenant}' AND reason='{marker}'
 AND expiry_date>CURRENT_DATE+30""").splitlines())
assert len(excluded)==5
base=f"/api/v1/purchasing/lot-lifecycle/warehouses/{warehouse}/expiry-alerts"
assert len(call(base))==300  # compatible old response, not complete
rows=[];cursor=None;pages=0
while True:
    args={"limit":50}
    if cursor:args["cursor"]=cursor
    data=call(base+"/page?"+urlencode(args))
    assert data["pageSize"]==50 and len(data["items"])<=50
    assert data["hasMore"]==bool(data["nextCursor"])
    rows.extend(data["items"])
    pages+=1
    assert pages<=100
    if not data["hasMore"]:break
    cursor=data["nextCursor"]
    assert len(data["items"])==50
ids=[r["positionId"] for r in rows]
found=set(ids)
assert len(ids)==len(found),("duplicate expiry positions",len(ids),len(found))
assert set(expected).issubset(found),(pages,len(set(expected)&found),len(expected))
assert not excluded.intersection(found)
assert pages>=7,pages
assert all(r["urgency"]==expected[r["positionId"]]
           for r in rows if r["positionId"] in expected)
assert all((rows[i]["expiryDate"],rows[i]["positionId"])<=
           (rows[i+1]["expiryDate"],rows[i+1]["positionId"])
           for i in range(len(rows)-1))
good_cursor=call(base+"/page?limit=50")["nextCursor"]
assert good_cursor
for invalid in ({"limit":0},{"limit":101},{"limit":50,"cursor":"invalid%"}):
    call(base+"/page?"+urlencode(invalid),400)
call(f"/api/v1/purchasing/lot-lifecycle/warehouses/{uuid4()}/expiry-alerts/page?"+
     urlencode({"limit":50,"cursor":good_cursor}),400)
deleted=sql(f"""WITH d AS (
 DELETE FROM erp_local_lot_position
 WHERE tenant_id='{tenant}' AND reason='{marker}' RETURNING id
) SELECT count(*) FROM d""")
assert deleted=="330"
assert sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")==before_stock
print("PASS V134 PG18 true Owner HTTP: 325 expiry alerts in 7+ pages; "+
      "160 expired, 165 near-expiry, 5 future excluded, legacy 300 retained; "+
      "keyset order, no-store, 401/400, no warehouse or Amazon posts")
