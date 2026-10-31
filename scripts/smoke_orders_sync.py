#!/usr/bin/env python3
"""V11A durable Orders Worker: synthetic localhost HTTP + PG + restart recovery."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener

parser = argparse.ArgumentParser()
parser.add_argument("--stage", required=True, choices=("before-restart","after-restart"))
args = parser.parse_args()
base = "http://127.0.0.1:3000"
state_file=Path("/tmp/merchivra-mock-smoke-state.json")
mp=json.loads(state_file.read_text())["usMarketplace"]
prefix="/api/v1/orders-sync/"+mp
opener=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

def request(path, method="GET", csrf=None):
    headers={"Accept":"application/json"}
    if csrf: headers["X-CSRF-TOKEN"]=csrf
    try:
        with opener.open(Request(base+path,method=method,headers=headers),timeout=25) as r:
            code, raw=r.status,r.read()
    except HTTPError as e:
        code,raw=e.code,e.read()
    try: return code,json.loads(raw)
    except (UnicodeError,ValueError): return code,raw.decode("utf-8",errors="replace")

def expect(path,status=200,**kwargs):
    code,value=request(path,**kwargs)
    assert code==status,(path,status,code,str(value)[:190])
    return value

expect(prefix,401)
csrf=expect("/api/v1/auth/csrf")["token"]
credentials=urlencode({
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]
}).encode()
with opener.open(Request(base+"/api/v1/auth/login",method="POST",data=credentials,
        headers={"Content-Type":"application/x-www-form-urlencoded","X-CSRF-TOKEN":csrf}),
        timeout=25) as response:
    assert response.status==200
csrf=expect("/api/v1/auth/csrf")["token"]

if args.stage=="before-restart":
    assert expect(prefix)["state"]=="NOT_STARTED"
    expect(prefix+"/start",403,method="POST")
    first=expect(prefix+"/start",method="POST",csrf=csrf)
    assert first["state"]=="READY" and first["committedPages"]==0
    assert expect(prefix+"/start",method="POST",csrf=csrf)["jobId"]==first["jobId"]
    initial=expect("/api/v1/seller-marketplaces/"+mp+"/orders")
    page=expect(prefix+"/step",method="POST",csrf=csrf)
    assert page["state"]=="READY" and page["committedPages"]==1
    assert page["lastSafeWatermark"] is None
    state_file.with_suffix(".v11.json").write_text(json.dumps({
        "jobId":page["jobId"], "orderCount":len(initial), "epoch":page["leaseEpoch"]
    }))
    print("PASS V11A stage 1: Session/CSRF, one durable page, no premature watermark")
else:
    saved=json.loads(state_file.with_suffix(".v11.json").read_text())
    recovered=expect(prefix)
    assert recovered["jobId"]==saved["jobId"]
    assert recovered["committedPages"]==1
    assert recovered["leaseEpoch"]==saved["epoch"]
    assert recovered["lastSafeWatermark"] is None
    last=expect(prefix+"/step",method="POST",csrf=csrf)
    assert last["state"]=="SUCCEEDED" and last["committedPages"]==2
    assert last["lastSafeWatermark"]
    expect(prefix+"/step",409,method="POST",csrf=csrf)
    orders=expect("/api/v1/seller-marketplaces/"+mp+"/orders")
    assert len(orders)==saved["orderCount"], "Source retry must not duplicate orders"
    assert all(o["dataOrigin"]=="MOCK" for o in orders)
    renewed=expect(prefix+"/start",method="POST",csrf=csrf)
    assert renewed["state"]=="READY" and renewed["committedPages"]==0
    assert renewed["lastSafeWatermark"]==last["lastSafeWatermark"]
    expect("/api/v1/orders-sync/ffffffff-ffff-ffff-ffff-ffffffffffff",404)
    print("PASS V11A stage 2: recovered after actual backend restart, page2, idempotence, watermark, foreign-scope denial")

expect("/api/v1/auth/logout",204,method="POST",csrf=csrf)
expect(prefix,401)
