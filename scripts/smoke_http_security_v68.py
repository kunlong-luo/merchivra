#!/usr/bin/env python3
"""V68 disposable localhost HTTP security gate. No real OAuth or external calls."""
import http.cookiejar
import json
import os
import statistics
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import uuid4

BASE = "http://127.0.0.1:8080"
assert os.environ.get("MERCHIVRA_V68_DISPOSABLE_ONLY") == "true"
assert os.environ.get("MERCHIVRA_SPAPI_ENABLED") == "false"
assert os.environ.get("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
jar = http.cookiejar.CookieJar()
opener = build_opener(HTTPCookieProcessor(jar))

def call(path, method="GET", body=None, csrf=None, expected=200, form=False):
    headers = {"Accept":"application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (urlencode(body) if form else json.dumps(body)).encode()
    if csrf: headers["X-CSRF-TOKEN"] = csrf
    try:
        with opener.open(Request(BASE+path,data=data,headers=headers,method=method),timeout=8) as response:
            status, payload, hs = response.status, response.read(), response.headers
    except HTTPError as error:
        status, payload, hs = error.code, error.read(), error.headers
    assert status == expected, (path,status,expected,payload[:160])
    return payload, hs

def body(path, expected=200):
    raw,_=call(path,expected=expected)
    return json.loads(raw) if raw else None

# No anonymous data, not even in newly added FBA, Ads and attention endpoints.
for path in ["/api/v1/auth/me","/api/v1/seller-accounts",
 "/api/v1/operations/findings","/api/v1/operations/summary",
 "/api/v1/operations/findings/"+str(uuid4())+"/attention-audit",
 "/api/v1/ads/marketplaces/"+str(uuid4())+"/reports",
 "/api/v1/fba/marketplaces/"+str(uuid4())+"/shipments"]:
    call(path,expected=401)
call("/api/v1/operations/scan",method="POST",body={},expected=403)
raw, headers=call("/actuator/health")
assert json.loads(raw)=={"status":"UP"},raw[:140]
assert "no-store" in headers.get("Cache-Control","")
assert headers.get("X-Content-Type-Options","").lower()=="nosniff"
# Spring Security default anti-framing headers apply to authenticated APIs as well.
assert headers.get("X-Frame-Options","").upper()=="DENY"

token=body("/api/v1/auth/csrf")["token"]
assert len(token)>=10
call("/api/v1/auth/login",method="POST",csrf=token,form=True,
     body={"username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
           "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]})
viewer,headers=call("/api/v1/auth/me")
assert json.loads(viewer)["isOwner"] is True
assert "no-store" in headers.get("Cache-Control","")
assert len([c for c in jar if c.name=="JSESSIONID"])==1
for cookie in jar:
    if cookie.name=="JSESSIONID":
        assert cookie.has_nonstandard_attr("HttpOnly"),"Session cookie must be HttpOnly"
        assert cookie.get_nonstandard_attr("SameSite","").lower()=="strict","Session cookie must be SameSite=Strict"

# A valid authenticated session without a CSRF header still cannot mutate.
call("/api/v1/operations/scan",method="POST",body={},expected=403)
call("/api/v1/operations/findings/"+str(uuid4())+"/claim",
     method="POST",body={},expected=403)
call("/api/v1/auth/logout",method="POST",expected=403)
token=body("/api/v1/auth/csrf")["token"]
times=[]
for _ in range(35):
    begin=time.perf_counter()
    raw,headers=call("/api/v1/operations/findings?filter=ACTIVE")
    assert isinstance(json.loads(raw),list)
    assert "no-store" in headers.get("Cache-Control","")
    times.append((time.perf_counter()-begin)*1000)
p95=sorted(times)[int(0.95*(len(times)-1))]
assert p95 < 2500, f"Single synthetic local V68 operations read p95={p95:.0f} ms"
call("/api/v1/auth/logout",method="POST",csrf=token,expected=204)
call("/api/v1/auth/me",expected=401)
call("/api/v1/operations/findings",expected=401)
print(f"PASS V68 HTTP: deny anonymous reads, CSRF, session HttpOnly/SameSite, safe headers, logout invalidation; synthetic 35 GET p95={p95:.0f}ms (not a load test)")
