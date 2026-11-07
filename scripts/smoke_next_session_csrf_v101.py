#!/usr/bin/env python3
"""V101: real Spring Session + CSRF through the Next.js same-origin proxy.

Disposable localhost CI ONLY. Never authorizes Amazon or contacts external hosts.
"""
import http.cookiejar
import json
import os
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener

BASE = "http://127.0.0.1:3355"
assert os.environ.get("MERCHIVRA_V101_DISPOSABLE_ONLY") == "true"
assert os.environ.get("MERCHIVRA_SPAPI_ENABLED") == "false"
assert os.environ.get("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
assert os.environ.get("MERCHIVRA_BOOTSTRAP_USERNAME")
assert os.environ.get("MERCHIVRA_BOOTSTRAP_PASSWORD")
jar = http.cookiejar.CookieJar()
opener = build_opener(HTTPCookieProcessor(jar))


def call(path, method="GET", body=None, csrf=None, expected=200, form=False,
         client=opener):
    assert path.startswith("/") and not path.startswith("//")
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = (
            "application/x-www-form-urlencoded" if form else "application/json"
        )
        data = (urlencode(body) if form else json.dumps(body)).encode()
    if csrf:
        headers["X-CSRF-TOKEN"] = csrf
    try:
        with client.open(Request(BASE + path, headers=headers, data=data,
                                 method=method), timeout=10) as response:
            status, payload, response_headers = (
                response.status, response.read(), response.headers
            )
    except HTTPError as error:
        status, payload, response_headers = error.code, error.read(), error.headers
    assert status == expected, f"{path}: HTTP {status}, expected {expected}"
    return payload, response_headers


def parsed(path, expected=200, client=opener):
    payload, headers = call(path, expected=expected, client=client)
    return (json.loads(payload) if payload else None), headers


# A working Next process must proxy Spring endpoints, not return mock responses.
ping, _ = parsed("/api/v1/ping")
assert ping["service"] == "merchivra" and ping["status"] == "ok"
assert ping["stage"]
call("/api/v1/auth/me", expected=401)
call("/api/v1/operations/findings", expected=401)

csrf_response, _ = parsed("/api/v1/auth/csrf")
assert csrf_response["headerName"] == "X-CSRF-TOKEN"
token = csrf_response["token"]
assert isinstance(token, str) and len(token) >= 10

# Without a CSRF token, a write is blocked even with the anonymous session.
form = {
    "username": os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password": os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"],
}
call("/api/v1/auth/login", method="POST", body=form, form=True, expected=403)
call("/api/v1/auth/me", expected=401)
call("/api/v1/auth/login", method="POST", body=form, form=True, csrf=token)

viewer, headers = parsed("/api/v1/auth/me")
assert viewer["isOwner"] is True
assert viewer["userId"] and viewer["activeTenantId"]
assert viewer["activeTenantId"] in viewer["availableTenantIds"]
assert "no-store" in headers.get("Cache-Control", "").lower()

sessions = [cookie for cookie in jar if cookie.name == "JSESSIONID"]
assert len(sessions) == 1, "Next proxy must preserve the real JSESSIONID"
assert sessions[0].has_nonstandard_attr("HttpOnly")
assert sessions[0].get_nonstandard_attr("SameSite", "").lower() == "strict"

# A second browser with no cookies must remain unauthenticated.
anonymous_client = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
call("/api/v1/auth/me", expected=401, client=anonymous_client)
findings, headers = parsed("/api/v1/operations/findings?filter=ACTIVE")
assert isinstance(findings, list)
assert "no-store" in headers.get("Cache-Control", "").lower()

# Authenticated HTML itself must not server-render a user's tenant or token.
page, _ = call("/finance")
assert viewer["activeTenantId"].encode() not in page
assert viewer["userId"].encode() not in page
assert token.encode() not in page
assert form["password"].encode() not in page

# Spring must reject writes without the anti-forgery header through Next.
call("/api/v1/operations/scan", method="POST", body={}, expected=403)
call("/api/v1/auth/logout", method="POST", expected=403)
csrf_response, _ = parsed("/api/v1/auth/csrf")
call("/api/v1/auth/logout", method="POST",
     csrf=csrf_response["token"], expected=204)
call("/api/v1/auth/me", expected=401)
call("/api/v1/operations/findings", expected=401)
print("PASS V101 Next proxy: real Spring login, HttpOnly/SameSite session, "
      "CSRF deny/allow, tenant isolation, logout invalidation, no SSR user data "
      "(disposable PG18 backend only)")
