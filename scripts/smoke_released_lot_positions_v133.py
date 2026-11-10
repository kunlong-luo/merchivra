#!/usr/bin/env python3
"""V133 real PG18/HTTP read-only all-positions and exact receipt-source test.

Runs AFTER V95 and V132 disposable fixture scripts; creates + deletes 325
additional local read fixtures with direct SQL only. No stock or remote writes.
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
    job=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
        text=True,capture_output=True,env=env,check=False)
    assert job.returncode==0,(query[:250],job.stderr[-1200:])
    return job.stdout.strip()

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
    if "/positions/page?" in path or "/allocation" in path:
        assert "no-store" in cache,(path,cache)
    return json.loads(raw)

call(f"/api/v1/purchasing/released-lots/warehouses/{uuid4()}/positions/page?limit=50",401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login",method="POST",body={
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},csrf=csrf)
me=call("/api/v1/auth/me")
assert me["isOwner"]
tenant=me["activeTenantId"]
base=sql(f"""SELECT p.id,p.warehouse_id,p.receipt_id,p.product_sku_id,p.bin_id
 FROM erp_local_lot_position p
 WHERE p.tenant_id='{tenant}' AND p.reason='V95 synthetic nonposting evidence position'
 LIMIT 1""").split("|")
assert len(base)==5,base
position,warehouse,receipt,sku,bin_id=base
source=f"/api/v1/purchasing/released-lots/warehouses/{warehouse}/positions"
stock_before=sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")
marker="V133 synthetic read-only batch position fixture"
new_count=sql(f"""WITH added AS (
 INSERT INTO erp_local_lot_position
 (id,tenant_id,request_key,receipt_id,warehouse_id,product_sku_id,bin_id,
  placed_quantity,remaining_quantity,reason,created_by)
 SELECT gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),'{receipt}'::uuid,
 '{warehouse}'::uuid,'{sku}'::uuid,'{bin_id}'::uuid,
 1,1,'{marker}','{me["userId"]}'::uuid
 FROM generate_series(1,325) RETURNING id
) SELECT count(*) FROM added""")
assert new_count=="325"
expected_ids=set(sql(f"""SELECT id FROM erp_local_lot_position
 WHERE tenant_id='{tenant}' AND reason='{marker}'""").splitlines())
assert len(expected_ids)==325

# Original 300 cap remains for compatibility, but cannot be mistaken for a
# complete history in any of the current React workspaces.
assert len(call(source))==300
rows=[];cursor=None;pages=0
while True:
    query={"limit":50}
    if cursor:query["cursor"]=cursor
    data=call(source+"/page?"+urlencode(query))
    assert data["pageSize"]==50 and len(data["items"])<=50
    rows.extend(data["items"])
    pages+=1
    assert pages<100
    if not data["hasMore"]:
        assert data["nextCursor"] is None
        break
    cursor=data["nextCursor"]
    assert cursor
got={row["id"] for row in rows}
assert expected_ids.issubset(got),(pages,len(got),len(expected_ids & got))
assert len(got)==len(rows),("duplicate positions",len(got),len(rows))
assert pages>=7,(pages,len(rows))
allocation=call(f"/api/v1/purchasing/released-lots/receipts/{receipt}/allocation")
# This is a deliberately PENDING_QC fixture: it must NOT become usable stock.
# Yet the root-only allocation must count all synthetic rows, not the first 300.
assert allocation["receiptId"]==receipt
assert allocation["warehouseId"]==warehouse
assert allocation["status"]!="RELEASED"
assert allocation["placedQuantity"]==326,allocation
assert allocation["remainingQuantity"]==0,allocation
valid_cursor=call(source+"/page?limit=50")["nextCursor"]
assert valid_cursor
for invalid in ({"limit":0},{"limit":101},{"cursor":"bad%"},
                {"limit":50,"cursor":valid_cursor,"wrong":True}):
    params=invalid.copy()
    path=source
    if params.pop("wrong",False):
        path=f"/api/v1/purchasing/released-lots/warehouses/{uuid4()}/positions"
    call(path+"/page?"+urlencode(params),400)
call(f"/api/v1/purchasing/released-lots/receipts/{uuid4()}/allocation",404)

# Do not leave even synthetic source positions in this shared temporary run.
removed=sql(f"""WITH deleted AS (
 DELETE FROM erp_local_lot_position
 WHERE tenant_id='{tenant}' AND reason='{marker}' RETURNING id
) SELECT count(*) FROM deleted""")
assert removed=="325"
assert sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")==stock_before
print("PASS V133 true PG18 HTTP: 325 more source positions accessible across "+
      "7+ keyset pages, receipt-root aggregation beyond 300, pending QC capacity "+
      "zero, Owner/401/400/404/no-store and no stock or Amazon writes")
