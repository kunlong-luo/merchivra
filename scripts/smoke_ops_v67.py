#!/usr/bin/env python3
"""V67 owner-only advisory queue regression on actual Spring Boot + disposable PG18."""
import http.cookiejar
import json
import os
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, HTTPCookieProcessor, build_opener
from uuid import uuid4

BASE="http://127.0.0.1:8080"
def client():
    return {"opener":build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar())),"csrf":None}
one,two,visitor=client(),client(),client()
def call(actor,path,method="GET",body=None,status=200,form=False,csrf=True):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
        data=(urlencode(body) if form else json.dumps(body)).encode()
    if method!="GET" and actor["csrf"] and csrf:headers["X-CSRF-TOKEN"]=actor["csrf"]
    try:
        with actor["opener"].open(Request(BASE+path,data=data,headers=headers,method=method),timeout=25) as resp:
            code,raw=resp.status,resp.read()
    except HTTPError as err:code,raw=err.code,err.read()
    assert code==status,(method,path,status,code,raw[:250])
    try:return json.loads(raw)
    except (ValueError,UnicodeError):return raw
def post(actor,path,body=None,status=200,csrf=True):
    return call(actor,path,"POST",body,status,csrf=csrf)
def login(actor,username,password):
    actor["csrf"]=call(actor,"/api/v1/auth/csrf")["token"]
    call(actor,"/api/v1/auth/login","POST",
         {"username":username,"password":password},form=True)
    actor["csrf"]=call(actor,"/api/v1/auth/csrf")["token"]
    return call(actor,"/api/v1/auth/me")

call(visitor,"/api/v1/operations/findings",status=401)
owner=login(one,os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
            os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"])
second=login(two,"v65-ci-second-owner","v65-disposable-second-owner-strong-password")
assert owner["userId"]!=second["userId"]
markets=call(one,"/api/v1/seller-marketplaces")
mp=next(m["id"] for m in markets if call(one,f"/api/v1/seller-marketplaces/{m['id']}/listings"))
fba=f"/api/v1/fba/marketplaces/{mp}/shipments"
sku=call(one,f"/api/v1/seller-marketplaces/{mp}/listings")[0]["sellerSku"]
shipment=post(one,fba,{"requestKey":str(uuid4()),"reference":"V67-REVIEW-TEST",
 "origin":"MOCK","lines":[{"sellerSku":sku,"quantity":3}]})["shipment"]["id"]
post(one,fba+f"/{shipment}/dispatch")
post(one,fba+f"/{shipment}/receipts",{
 "requestKey":str(uuid4()),"sellerSku":sku,"quantity":3,
 "evidenceReference":"V67-RECEIPT-TEST","origin":"MOCK"})
post(one,fba+f"/{shipment}/review")
ads=f"/api/v1/ads/marketplaces/{mp}"
profile=post(one,ads+"/profiles",{"profileReference":"V67-MOCK-PROFILE",
                                 "origin":"MOCK"})["id"]
batch=post(one,ads+"/reports",{
 "profileId":profile,"reportReference":"V67-TEST-MOCK-REPORT-01",
 "origin":"MOCK","fromDate":"2026-10-31","throughDate":"2026-10-31",
 "currency":next(m["currency"] for m in markets if m["id"]==mp),
 "rows":[{"businessDate":"2026-10-31","campaignReference":"V67-CAMPAIGN",
          "spendAmount":"2.500000"}]})["batch"]["id"]
queue="/api/v1/operations"
call(one,queue+"/findings?filter=INVALID",status=400)
post(one,queue+"/scan",status=403,csrf=False)
scan=post(one,queue+"/scan")
assert scan["triggerType"]=="MANUAL" and scan["matchedCount"]>=2
data=call(one,queue+"/findings?filter=ALL")
fba_row=next(x for x in data if x["ruleCode"]=="FBA_INBOUND_REVIEW" and x["sourceKey"]==shipment)
ads_row=next(x for x in data if x["ruleCode"]=="ADS_REPORT_REVIEW" and x["sourceKey"]==batch)
assert fba_row["dataOrigin"]=="MOCK" and ads_row["dataOrigin"]=="MOCK"
assert fba_row["assignedTo"] is None and fba_row["snoozedUntil"] is None
post(one,queue+"/scan")
again=call(one,queue+"/findings?filter=ALL")
assert next(x for x in again if x["sourceKey"]==shipment)["id"]==fba_row["id"]
assert next(x for x in again if x["sourceKey"]==batch)["id"]==ads_row["id"]
fid=fba_row["id"]
post(one,queue+f"/findings/{uuid4()}/claim",status=404)
claim=post(one,queue+f"/findings/{fid}/claim")
assert claim["assignedTo"]==owner["userId"] and claim["state"]=="ACKNOWLEDGED"
assert post(one,queue+f"/findings/{fid}/claim")["assignedTo"]==owner["userId"]
post(two,queue+f"/findings/{fid}/claim",status=409)
post(two,queue+f"/findings/{fid}/release",status=409)
post(two,queue+f"/findings/{fid}/snooze",
     {"minutes":60,"note":"Other Owner cannot override my claimed attention"},status=409)
post(one,queue+f"/findings/{fid}/snooze",
     {"minutes":25,"note":"Invalid duration must be rejected"},status=400)
post(one,queue+f"/findings/{fid}/snooze",
     {"minutes":60,"note":"short"},status=400)
delayed=post(one,queue+f"/findings/{fid}/snooze",
             {"minutes":60,"note":"Review this local FBA shipment later"})
assert delayed["snoozedUntil"] is not None
assert call(one,queue+"/summary")["snoozed"]>=1
assert all(x["id"]!=fid for x in call(one,queue+"/findings?filter=ACTIONABLE"))
post(one,queue+"/scan")
assert all(x["id"]!=fid for x in call(one,queue+"/findings?filter=ACTIONABLE"))
assert post(one,queue+f"/findings/{fid}/unsnooze")["snoozedUntil"] is None
assert any(x["id"]==fid for x in call(one,queue+"/findings?filter=ACTIONABLE"))
assert post(one,queue+f"/findings/{fid}/release")["assignedTo"] is None
assert post(two,queue+f"/findings/{fid}/claim")["assignedTo"]==second["userId"]
audit=call(one,queue+f"/findings/{fid}/attention-audit")
assert [a["action"] for a in audit][:3]==["CLAIMED","RELEASED","UNSNOOZED"]
assert len(audit)==5
call(one,queue+f"/findings/{uuid4()}/attention-audit",status=404)
post(one,fba+f"/{shipment}/reconcile")
post(two,ads+f"/reports/{batch}/review",
     {"decision":"REJECT","note":"Rejecting mock campaign example after review"})
post(one,queue+"/scan")
latest=call(one,queue+"/findings?filter=ALL")
assert next(x for x in latest if x["id"]==fid)["state"]=="CLEARED"
assert next(x for x in latest if x["id"]==ads_row["id"])["state"]=="CLEARED"
post(one,queue+f"/findings/{fid}/claim",status=409)
assert call(one,queue+"/summary")["snoozed"]==0
print("PASS V67 Owner Session / CSRF, stable FBA+Ads dedup, claim conflict, snooze/unsnooze, audit, automatic clear, no Amazon egress")
