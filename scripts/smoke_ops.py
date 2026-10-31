#!/usr/bin/env python3
"""V10 authenticated local-only operations automation end-to-end smoke."""
import http.cookiejar
import json
import os
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener

base = "http://127.0.0.1:3000"
opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

def req(path, method="GET", body=None, csrf=None):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if csrf:
        headers["X-CSRF-TOKEN"] = csrf
    try:
        with opener.open(Request(base + path, data=data, method=method, headers=headers), timeout=25) as r:
            code, raw = r.status, r.read()
    except HTTPError as e:
        code, raw = e.code, e.read()
    try:
        return code, json.loads(raw)
    except (ValueError, UnicodeError):
        return code, raw.decode("utf8", errors="replace")

def expect(path, status=200, **kwargs):
    code, data = req(path, **kwargs)
    assert code == status, (path, code, status, str(data)[:190])
    return data

expect("/api/v1/operations/summary", 401)
token = expect("/api/v1/auth/csrf")["token"]
login = urlencode({
    "username": os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password": os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]
}).encode()
try:
    with opener.open(Request(base + "/api/v1/auth/login",
                             data=login, method="POST",
                             headers={"Content-Type":"application/x-www-form-urlencoded",
                                      "X-CSRF-TOKEN":token}), timeout=25) as r:
        assert r.status == 200
except HTTPError as error:
    raise AssertionError("Fixture admin login failed: " + str(error.code))
token = expect("/api/v1/auth/csrf")["token"]
assert expect("/api/v1/operations/summary")["open"] == 0
expect("/api/v1/operations/scan", 403, method="POST")  # CSRF is mandatory.
first = expect("/api/v1/operations/scan", method="POST", csrf=token)
assert first["triggerType"] == "MANUAL" and first["matchedCount"] > 0
findings = expect("/api/v1/operations/findings?filter=ACTIVE")
assert len(findings) > 0 and all(x["state"] == "OPEN" for x in findings)
assert all(x["dataOrigin"] == "MOCK" for x in findings)
assert set(x["ruleCode"] for x in findings).issubset(
    {"UNMAPPED_LISTING", "INVENTORY_UNKNOWN", "LOW_STOCK", "REFUND_REVIEW"})
before = len(findings)
second = expect("/api/v1/operations/scan", method="POST", csrf=token)
assert second["matchedCount"] == first["matchedCount"]
assert len(expect("/api/v1/operations/findings?filter=ACTIVE")) == before
item = findings[0]["id"]
expect("/api/v1/operations/findings/" + str(uuid.uuid4()) + "/state",
       404, method="POST", body={"state":"DONE"}, csrf=token)
expect("/api/v1/operations/findings/" + item + "/state",
       400, method="POST", body={"state":"CLEARED"}, csrf=token)
assert expect("/api/v1/operations/findings/" + item + "/state",
              method="POST", body={"state":"ACKNOWLEDGED"}, csrf=token)["state"] == "ACKNOWLEDGED"
assert expect("/api/v1/operations/findings/" + item + "/state",
              method="POST", body={"state":"DONE"}, csrf=token)["state"] == "DONE"
expect("/api/v1/operations/scan", method="POST", csrf=token)
all_rows = expect("/api/v1/operations/findings?filter=ALL")
assert len(all_rows) == before
assert next(x for x in all_rows if x["id"] == item)["state"] == "DONE"
summary = expect("/api/v1/operations/summary")
assert summary["done"] == 1 and summary["open"] == before - 1
expect("/api/v1/auth/logout", 204, method="POST", csrf=token)
expect("/api/v1/operations/findings", 401)
print("PASS V10: real PG + HTTP + Session/CSRF, scan dedupe, source provenance, triage, idempotent DONE, scoped 404 and logout")
