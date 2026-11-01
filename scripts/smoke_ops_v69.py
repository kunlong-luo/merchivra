#!/usr/bin/env python3
"""V69 locally authenticated PG18 Mock-only rule-settings regression.

Runs against the disposable CI Spring MVC + PG18 instance; no Seller/Ads egress.
"""
import http.cookiejar
import json
import os
from uuid import uuid4
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, HTTPCookieProcessor, build_opener

assert os.environ.get("MERCHIVRA_DEMO_ENABLED")=="true"
assert os.environ.get("MERCHIVRA_SPAPI_ENABLED")=="false"
base="http://127.0.0.1:8080"
client=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=None

def call(path,method="GET",body=None,expected=200,form=False,headers=None):
    h={"Accept":"application/json",**(headers or {})}
    data=None
    if body is not None:
        h["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
        data=(urlencode(body) if form else json.dumps(body)).encode()
    if csrf is not None and method!="GET":h["X-CSRF-TOKEN"]=csrf
    try:
        with client.open(Request(base+path,data=data,headers=h,method=method),timeout=20) as r:
            code,raw=r.status,r.read()
    except HTTPError as err:
        code,raw=err.code,err.read()
    assert code==expected,(method,path,code,expected,raw[:250])
    try:return json.loads(raw)
    except (ValueError,UnicodeError):return raw

def post(path,body=None,expected=200,headers=None):
    return call(path,"POST",body or {},expected,headers=headers)

call("/api/v1/operations/marketplaces/"+str(uuid4())+"/rules",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",
 {"username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
  "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]

# Use two independent Seller Account/Marketplace records. Both use invented fixtures.
def new_site(tag):
    acc=post("/api/v1/seller-accounts",{"displayName":"V69-"+tag,"region":"NA"},
        expected=201,headers={"Idempotency-Key":"v69-"+tag})["id"]
    mp=post("/api/v1/seller-accounts/"+acc+"/marketplaces",
        {"marketplaceId":"ATVPDKIKX0DER"},expected=201)["id"]
    post("/api/v1/demo/sandbox/"+mp+"/replay",
       {"simulateFailure":False},expected=201,
       headers={"Idempotency-Key":"v69-fixture-"+tag})
    return mp
mp1,mp2=new_site("PRIMARY"),new_site("OTHER")
path=lambda mp:"/api/v1/operations/marketplaces/"+mp+"/rules"
all_rules=lambda mp:{r["ruleCode"]:r for r in call(path(mp))}
one,two=all_rules(mp1),all_rules(mp2)
assert len(one)==6 and len(two)==6
assert all(r["enabled"] and r["version"]==0 for r in one.values())
assert one["LOW_STOCK"]["lowStockThreshold"]==3
assert one["REFUND_REVIEW"]["lowStockThreshold"] is None

foreign=str(uuid4())
call(path(foreign),expected=404)
post(path(foreign)+"/LOW_STOCK",
 {"enabled":False,"lowStockThreshold":4,"expectedVersion":0},expected=404)
post(path(mp1)+"/UNKNOWN",
 {"enabled":False,"lowStockThreshold":None,"expectedVersion":0},expected=400)
post(path(mp1)+"/REFUND_REVIEW",
 {"enabled":False,"lowStockThreshold":5,"expectedVersion":0},expected=400)
post(path(mp1)+"/LOW_STOCK",
 {"enabled":True,"lowStockThreshold":10001,"expectedVersion":0},expected=400)
post(path(mp1)+"/UNMAPPED_LISTING",
 {"enabled":False,"lowStockThreshold":None,"expectedVersion":-1},expected=400)
post(path(mp1)+"/UNMAPPED_LISTING",
 {"lowStockThreshold":None,"expectedVersion":0},expected=400)
post(path(mp1)+"/UNMAPPED_LISTING",
 {"enabled":False,"lowStockThreshold":None},expected=400)

queue="/api/v1/operations"
post(queue+"/scan")
current=call(queue+"/findings?filter=ACTIVE")
before1=[r for r in current if r["marketplaceId"]==mp1 and r["ruleCode"]=="UNMAPPED_LISTING"]
before2=[r for r in current if r["marketplaceId"]==mp2 and r["ruleCode"]=="UNMAPPED_LISTING"]
assert before1 and before2,"Each independent Mock shop needs an UNMAPPED Listing"

disabled=post(path(mp1)+"/UNMAPPED_LISTING",
 {"enabled":False,"expectedVersion":0})
assert disabled["version"]==1 and disabled["enabled"] is False
assert disabled["lowStockThreshold"] is None
post(path(mp1)+"/UNMAPPED_LISTING",
 {"enabled":True,"expectedVersion":0},expected=409)
idempotent=post(path(mp1)+"/UNMAPPED_LISTING",
 {"enabled":False,"expectedVersion":1})
assert idempotent["version"]==1,"No-op must not increment revision"
assert all_rules(mp2)["UNMAPPED_LISTING"]["version"]==0

# A saved rule change does not pretend to mutate sources; the NEXT scan clears
# disabled findings, and it never alters the separate Marketplace.
post(queue+"/scan")
active=call(queue+"/findings?filter=ACTIVE")
assert not any(r["marketplaceId"]==mp1 and r["ruleCode"]=="UNMAPPED_LISTING" for r in active)
assert any(r["marketplaceId"]==mp2 and r["ruleCode"]=="UNMAPPED_LISTING" for r in active)
cleared=call(queue+"/findings?filter=ALL")
assert any(r["id"]==before1[0]["id"] and r["state"]=="CLEARED" for r in cleared)
enabled=post(path(mp1)+"/UNMAPPED_LISTING",
 {"enabled":True,"expectedVersion":1})
assert enabled["version"]==2 and enabled["enabled"]
post(queue+"/scan")
reopened=call(queue+"/findings?filter=ACTIVE")
assert any(r["id"]==before1[0]["id"] and r["state"]=="OPEN" for r in reopened)

high=post(path(mp1)+"/LOW_STOCK",
 {"enabled":True,"lowStockThreshold":10000,"expectedVersion":0})
assert high["version"]==1 and high["lowStockThreshold"]==10000
post(queue+"/scan")
inventory=call("/api/v1/seller-marketplaces/"+mp1+"/inventory/fba")
observed={x["sellerSku"] for x in inventory if x["quality"]=="OBSERVED" and
 x["fulfillable"] is not None and x["fulfillable"]<=10000}
if observed:
    active=call(queue+"/findings?filter=ACTIVE")
    assert observed.issubset({x["sourceKey"] for x in active
      if x["marketplaceId"]==mp1 and x["ruleCode"]=="LOW_STOCK"})

history=call(path(mp1)+"/audit")
assert len(history)==3,history
assert [x["ruleCode"] for x in history].count("UNMAPPED_LISTING")==2
assert [x["ruleCode"] for x in history].count("LOW_STOCK")==1
assert history[0]["newVersion"] in (1,2)
assert call(path(mp2)+"/audit")==[]

print("PASS V69: PG18 two Marketplace scoped rule defaults/override/CAS, disabled-rule clearing, re-enable, low-stock threshold and immutable audit")
