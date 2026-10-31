#!/usr/bin/env python3
"""V11A durable Orders Worker smoke: disposable localhost-only Mock source."""
import http.cookiejar
import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener

base = "http://127.0.0.1:3000"
mp = json.loads(Path("/tmp/merchivra-mock-smoke-state.json").read_text())["usMarketplace"]
opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

def request(path, method="GET", body=None, csrf=None):
    headers = {"Accept":"application/json"}
    data = None if body is None else json.dumps(body).encode()
    if data is not None: headers["Content-Type"]="application/json"
    if csrf: headers["X-CSRF-TOKEN"]=csrf
    try:
        with opener.open(Request(base+path,method=method,headers=headers,data=data),timeout=25) as r:
            status,raw=r.status,r.read()
    except HTTPError as e:
        status,raw=e.code,e.read()
    try: return status,json.loads(raw)
    except (UnicodeError,ValueError): return status,raw.decode("utf-8",errors="replace")

def expect(path,status=200,**kwargs):
    code,payload=request(path,**kwargs)
    assert code==status,(path,status,code,str(payload)[:190])
    return payload

prefix="/api/v1/orders-sync/"+mp
expect(prefix,401)
csrf=expect("/api/v1/auth/csrf")["token"]
login=Request(base+"/api/v1/auth/login",method="POST",
    data=urlencode({"username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
                    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]}).encode(),
    headers={"Content-Type":"application/x-www-form-urlencoded","X-CSRF-TOKEN":csrf})
with opener.open(login,timeout=25) as response: assert response.status==200
csrf=expect("/api/v1/auth/csrf")["token"]
assert expect(prefix)["state"]=="NOT_STARTED"
expect(prefix+"/start",403,method="POST")  # CSRF mandatory
job=expect(prefix+"/start",method="POST",csrf=csrf)
assert job["state"]=="READY" and job["committedPages"]==0
assert expect(prefix+"/start",method="POST",csrf=csrf)["jobId"]==job["jobId"]
before=expect("/api/v1/seller-marketplaces/"+mp+"/orders")
first=expect(prefix+"/step",method="POST",csrf=csrf)
assert first["state"]=="READY" and first["committedPages"]==1
assert first["lastSafeWatermark"] is None
assert expect(prefix)["committedPages"]==1
second=expect(prefix+"/step",method="POST",csrf=csrf)
assert second["state"]=="SUCCEEDED" and second["committedPages"]==2
assert second["lastSafeWatermark"]
expect(prefix+"/step",409,method="POST",csrf=csrf)
after=expect("/api/v1/seller-marketplaces/"+mp+"/orders")
assert len(after)==len(before), "Replay should deduplicate existing synthetic orders"
assert all(o["dataOrigin"]=="MOCK" for o in after)
new=expect(prefix+"/start",method="POST",csrf=csrf)
assert new["state"]=="READY" and new["committedPages"]==0
assert new["lastSafeWatermark"]==second["lastSafeWatermark"]
expect("/api/v1/orders-sync/"+("f"*8+"-"+("f"*4+"-")*3+"f"*12),404)
expect("/api/v1/auth/logout",204,method="POST",csrf=csrf)
expect(prefix,401)
print("PASS V11A: localhost HTTP+PG migration, OWNER Session/CSRF, incremental job, atomic 2-page cursor, safe watermark, dedup, restart window, foreign ID, logout")
