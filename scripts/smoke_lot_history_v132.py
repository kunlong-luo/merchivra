#!/usr/bin/env python3
"""V132 complete, read-only V49 source/destination action keyset on real PG18 HTTP.

Uses the 225 synthetic COUNT_OBSERVATION actions written by V95 earlier in
this same disposable full-suite run; never touches Amazon or real credentials.
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
assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES", "false") == "false"

http = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
def sql(query):
    env = {**os.environ, "PGPASSWORD": os.environ["DB_PASSWORD"]}
    run = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
        "-h", "127.0.0.1", "-U", "merchivra", "-d", "merchivra", "-Atc", query],
        env=env,text=True,capture_output=True,check=False)
    assert run.returncode == 0,(query[:240],run.stderr[-1000:])
    return run.stdout.strip()

def call(path,expected=200,method="GET",body=None,csrf=None):
    headers = {"Accept":"application/json"}
    if csrf: headers["X-CSRF-TOKEN"] = csrf
    if body is not None:
        data=urlencode(body).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded"
    else: data=None
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,headers=headers,
            method=method,data=data),timeout=50) as response:
            status,raw,cache=response.status,response.read(),response.headers.get("Cache-Control","")
    except HTTPError as err:
        status,raw,cache=err.code,err.read(),""
    assert status == expected,(path,status,expected,raw[:650])
    if expected!=200:return None
    if "/history/page?" in path:assert "no-store" in cache,(path,cache)
    return json.loads(raw)

position=str(uuid4())
call(f"/api/v1/purchasing/lot-lifecycle/positions/{position}/history/page?limit=50",401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login",method="POST",body={
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},csrf=csrf)
me=call("/api/v1/auth/me")
assert me["isOwner"]
tenant=me["activeTenantId"]
position=sql(f"""SELECT source_position_id FROM erp_local_lot_action
 WHERE tenant_id='{tenant}' AND reason='V95 synthetic observed surplus only'
 GROUP BY source_position_id HAVING count(*)=225 LIMIT 1""")
assert position and len(position)==36,position
path=f"/api/v1/purchasing/lot-lifecycle/positions/{position}/history"
assert len(call(path))==200  # compatible but explicitly capped legacy result
items=[]
cursor=None
pages=0
while True:
    params={"limit":50}
    if cursor:params["cursor"]=cursor
    page=call(path+"/page?"+urlencode(params))
    assert page["pageSize"]==50 and len(page["items"])<=50
    assert page["hasMore"]==bool(page["nextCursor"])
    items.extend(page["items"])
    pages+=1
    assert pages<=6
    if not page["hasMore"]:break
    cursor=page["nextCursor"]
assert pages==5 and len(items)==225,(pages,len(items))
assert len({r["id"] for r in items})==225
assert all(r["sourcePositionId"]==position and
           r["actionType"]=="COUNT_OBSERVATION" for r in items)
assert all(r["operationId"] is None for r in items)
assert sql(f"""SELECT count(*) FROM erp_local_lot_action
 WHERE tenant_id='{tenant}' AND source_position_id='{position}'
 AND reason='V95 synthetic observed surplus only'""")=="225"
valid_cursor=call(path+"/page?limit=50")["nextCursor"]
assert valid_cursor
for wrong in (
    {"limit":0},{"limit":101},{"limit":50,"cursor":"bad%cursor"},
    {"limit":50,"cursor":valid_cursor,"_wrong":True}
):
    query=wrong.copy()
    if query.pop("_wrong",False):
        call(f"/api/v1/purchasing/lot-lifecycle/positions/{uuid4()}/history/page?"+
             urlencode(query),400)
    else:call(path+"/page?"+urlencode(query),400)
call(f"/api/v1/purchasing/lot-lifecycle/positions/{uuid4()}/history/page?limit=50",404)
print("PASS V132 real PG18 HTTP: 225 source history actions in five 50-row pages, "+
      "legacy 200 retained, Owner login, cursor scope/limits 400, missing 404, "+
      "no-store and zero external API/stock writes")
