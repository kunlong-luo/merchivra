#!/usr/bin/env python3
"""Local-only simulated end-to-end chain: Nginx -> Session -> PG -> Mock replay.
Run ONLY against disposable localhost Compose with fake bootstrap credentials.
No third-party packages, no Amazon requests and no sensitive values printed.
"""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--base", default="http://127.0.0.1:3000")
parser.add_argument("--stage", choices=("initial", "restart"), default="initial")
parser.add_argument("--state", default="/tmp/merchivra-mock-smoke-state.json")
args = parser.parse_args()
if not args.base.startswith("http://127.0.0.1:") and not args.base.startswith("http://localhost:"):
    parser.error("Safety guard: only localhost is allowed")
username = os.environ.get("MERCHIVRA_BOOTSTRAP_USERNAME")
password = os.environ.get("MERCHIVRA_BOOTSTRAP_PASSWORD")
if not username or not password:
    parser.error("Set disposable MERCHIVRA_BOOTSTRAP_USERNAME/PASSWORD for local smoke")
base = args.base.rstrip("/")
opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

def request(path, *, method="GET", payload=None, form=False, headers=None):
    data = None
    h = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        if form:
            data = urlencode(payload).encode("utf-8")
            h["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(payload).encode("utf-8")
            h["Content-Type"] = "application/json"
    req = Request(base + path, data=data, headers=h, method=method)
    try:
        with opener.open(req, timeout=20) as response:
            code, raw = response.status, response.read()
    except HTTPError as error:
        code, raw = error.code, error.read()
    try:
        value = json.loads(raw)
    except (ValueError, UnicodeError):
        value = raw.decode("utf-8", errors="replace")
    return code, value

def expect(path, status=200, **options):
    code, value = request(path, **options)
    assert code == status, f"{path}: expected {status}, got {code}; {str(value)[:220]}"
    return value

def login():
    expect("/api/v1/seller-accounts", 401)
    csrf = expect("/api/v1/auth/csrf")["token"]
    expect("/api/v1/auth/login", 200, method="POST",
           payload={"username": username, "password": password}, form=True,
           headers={"X-CSRF-TOKEN": csrf})
    csrf = expect("/api/v1/auth/csrf")["token"]
    me = expect("/api/v1/auth/me")
    assert me["activeTenantId"] in me["availableTenantIds"]
    return csrf, me

def post(path, payload, key=None):
    headers = {"X-CSRF-TOKEN": csrf}
    if key:
        headers["Idempotency-Key"] = key
    return expect(path, 201, method="POST", payload=payload, headers=headers)

def assert_records(mp, *, listings, orders, inventory):
    path = f"/api/v1/seller-marketplaces/{mp}"
    ls = expect(path + "/listings")
    os_ = expect(path + "/orders")
    iv = expect(path + "/inventory/fba")
    assert (len(ls), len(os_), len(iv)) == (listings, orders, inventory), (ls, os_, iv)
    assert all(row["dataOrigin"] == "MOCK" for row in (ls + os_ + iv))
    assert all(row["financialStatus"] == "NOT_OBSERVED" for row in os_)
    return ls, os_, iv

# Browser and proxy are real: the HTML is served through Nginx port 3000.
code, html = request("/")
assert code == 200 and "html" in html.lower(), "SPA or nginx proxy unavailable"
csrf, me = login()
print("PASS: web proxy, anonymous 401, Session/CSRF, Owner tenant")

if args.stage == "restart":
    state = json.loads(Path(args.state).read_text())
    for key, expected in (("usMarketplace", (2, 2, 2)), ("euMarketplace", (1, 1, 1))):
        assert_records(state[key], listings=expected[0], orders=expected[1], inventory=expected[2])
    runs = expect("/api/v1/demo/sandbox/" + state["usMarketplace"] + "/runs")
    assert len(runs) == 2 and {x["status"] for x in runs} == {"SUCCEEDED", "FAILED_RETRYABLE"}
    print("PASS: container restart preserved synthetic facts and sync history")
    expect("/api/v1/auth/logout", 204, method="POST", headers={"X-CSRF-TOKEN": csrf})
    expect("/api/v1/seller-accounts", 401)
    print("PASS: logout revokes session")
    raise SystemExit(0)

# The fixed demo and newly authenticated tenant must be separate.
overview = expect("/api/v1/demo/overview")
assert overview["mode"] == "MOCK" and len(overview["orders"]) == 3
us_demo_account = overview["accounts"][0]["id"]
assert len(expect("/api/v1/demo/orders?accountId=" + us_demo_account)) == 2
expect("/api/v1/demo/orders?accountId=unknown", 404)
expect("/api/v1/demo/sync-runs", 403, method="POST", payload={"simulateFailure": False})
failure = post("/api/v1/demo/sync-runs", {"simulateFailure": True}, key="fullchain-demo-failure")
assert failure["status"] == "FAILED_RETRYABLE"
success = post("/api/v1/demo/sync-runs", {"simulateFailure": False}, key="fullchain-demo-success")
assert success["status"] == "SUCCEEDED"
replayed = post("/api/v1/demo/sync-runs", {"simulateFailure": False}, key="fullchain-demo-success")
assert replayed["id"] == success["id"]
core = expect("/api/v1/demo/core-status")
assert core["accounts"] == 2 and core["listings"] == 3 and core["orders"] == 3
print("PASS: isolated demo gateway, failure/success, idempotency and fixed Mock tenant")

def create_account(region, name, key):
    return post("/api/v1/seller-accounts",
                {"displayName": name, "region": region}, key=key)["id"]

us_acc = create_account("NA", "Sandbox Smoke US", "fullchain-local-account-us")
eu_acc = create_account("EU", "Sandbox Smoke EU", "fullchain-local-account-eu")
assert create_account("NA", "Sandbox Smoke US", "fullchain-local-account-us") == us_acc
expect("/api/v1/seller-accounts", 409, method="POST",
       payload={"displayName": "Changed", "region": "NA"},
       headers={"Idempotency-Key": "fullchain-local-account-us", "X-CSRF-TOKEN": csrf})
expect("/api/v1/seller-accounts/" + us_acc + "/marketplaces", 400,
       method="POST", payload={"marketplaceId": "A1PA6795UKMFR9"},
       headers={"X-CSRF-TOKEN": csrf})
us_mp = post("/api/v1/seller-accounts/" + us_acc + "/marketplaces",
             {"marketplaceId": "ATVPDKIKX0DER"})["id"]
eu_mp = post("/api/v1/seller-accounts/" + eu_acc + "/marketplaces",
             {"marketplaceId": "A1PA6795UKMFR9"})["id"]
assert post("/api/v1/seller-accounts/" + us_acc + "/marketplaces",
            {"marketplaceId": "ATVPDKIKX0DER"})["id"] == us_mp
all_mp = expect("/api/v1/seller-marketplaces")
assert len(all_mp) == 2 and {x["currency"] for x in all_mp} == {"USD", "EUR"}
print("PASS: local Seller setup, region validation, replay safety, curated Marketplaces")

# The real session Owner tenant must not accidentally see fixture-tenant facts.
assert_records(us_mp, listings=0, orders=0, inventory=0)
p = "/api/v1/demo/sandbox/" + us_mp
run_failure = post(p + "/replay", {"simulateFailure": True}, key="fullchain-us-fail")
assert run_failure["status"] == "FAILED_RETRYABLE"
assert_records(us_mp, listings=0, orders=0, inventory=0)
us_run = post(p + "/replay", {"simulateFailure": False}, key="fullchain-us-success")
assert us_run["status"] == "SUCCEEDED" and us_run["source"] == "MOCK"
assert (us_run["orderPageCount"], us_run["listingCount"], us_run["orderCount"],
        us_run["inventoryCount"]) == (2, 2, 2, 2)
assert us_run["financeStatus"] == "NOT_AUTHORIZED"
replayed = post(p + "/replay", {"simulateFailure": False}, key="fullchain-us-success")
assert replayed["id"] == us_run["id"]
expect(p + "/replay", 409, method="POST", payload={"simulateFailure": True},
       headers={"Idempotency-Key": "fullchain-us-success", "X-CSRF-TOKEN": csrf})

ls, orders, iv = assert_records(us_mp, listings=2, orders=2, inventory=2)
assert any(x["mappingStatus"] == "UNMAPPED" and x["internalSku"] is None for x in ls)
assert any(x["mappingStatus"] == "ACTIVE" and x["internalSku"].startswith("SANDBOX-") for x in ls)
assert any(x["refundStatus"] == "PARTIAL" and x["fulfillmentStatus"] == "SHIPPED"
           and x["financialStatus"] == "NOT_OBSERVED" for x in orders)
assert any(x["quality"] == "UNKNOWN" and x["fulfillable"] is None for x in iv)
assert any(x["quality"] == "OBSERVED" and x["fulfillable"] == 12 for x in iv)
assert all(x["itemCount"] == 1 for x in orders)
print("PASS: fake paged Orders -> canonical order items, Listing mappings and UNKNOWN inventory")

eu_run = post("/api/v1/demo/sandbox/" + eu_mp + "/replay",
              {"simulateFailure": False}, key="fullchain-eu-success")
assert eu_run["orderPageCount"] == 1 and eu_run["financeStatus"] == "NOT_AUTHORIZED"
ls, orders, iv = assert_records(eu_mp, listings=1, orders=1, inventory=1)
assert iv[0]["fulfillable"] == 0 and iv[0]["quality"] == "OBSERVED"
assert ls[0]["internalSku"].startswith("SANDBOX-")
# A separate EU tenant/marketplace must not contaminate USD orders.
assert orders[0]["externalOrderId"].startswith("SANDBOX-MOCK-EU-")
print("PASS: EU original currency separate, observed zero != UNKNOWN")

us_runs = expect(p + "/runs")
assert len(us_runs) == 2 and {x["status"] for x in us_runs} == {"FAILED_RETRYABLE", "SUCCEEDED"}
audit = expect("/api/v1/seller-accounts/audit")
assert len(audit) == 6, [x["action"] for x in audit]  # 2 account + 2 MP + 2 success
assert sum(x["action"] == "SANDBOX_FIXTURE_IMPORTED" for x in audit) == 2
expect("/api/v1/seller-marketplaces/33333333-3333-4333-8333-333333333333/orders", 404)
expect("/api/v1/demo/sandbox/33333333-3333-4333-8333-333333333333/replay",
       404, method="POST", payload={"simulateFailure": False},
       headers={"Idempotency-Key": "not-authorized-01", "X-CSRF-TOKEN": csrf})
print("PASS: run history, one audit per committed import, isolation and hidden IDs")

Path(args.state).write_text(json.dumps({"usMarketplace": us_mp, "euMarketplace": eu_mp}))
print("PASS: initial full fake integration chain complete")
