#!/usr/bin/env python3
"""V65 disposable, localhost-only FBA inbound HTTP smoke: no Amazon access."""
import http.cookiejar
import json
import os
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import uuid4

base = "http://127.0.0.1:8080"
username = os.environ.get("MERCHIVRA_BOOTSTRAP_USERNAME")
password = os.environ.get("MERCHIVRA_BOOTSTRAP_PASSWORD")
if not username or not password:
    raise SystemExit("Requires disposable local bootstrap username/password")
client = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = None


def call(path, method="GET", body=None, headers=None, expected=200, form=False):
    data = None
    request_headers = {"Accept": "application/json", **(headers or {})}
    if body is not None:
        if form:
            data = urlencode(body).encode()
            request_headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(body).encode()
            request_headers["Content-Type"] = "application/json"
    if method not in ("GET", "HEAD") and csrf is not None:
        request_headers["X-CSRF-TOKEN"] = csrf
    request = Request(base + path, data=data, headers=request_headers, method=method)
    try:
        with client.open(request, timeout=20) as response:
            status, payload = response.status, response.read()
    except HTTPError as error:
        status, payload = error.code, error.read()
    assert status == expected, f"{method} {path} status {status}, expected {expected}: {payload[:250]!r}"
    try:
        return json.loads(payload)
    except ValueError:
        return payload


def post(path, body=None, expected=200, headers=None):
    return call(path, method="POST", body=body or {}, expected=expected, headers=headers)


call("/api/v1/fba/marketplaces/" + str(uuid4()) + "/shipments", expected=401)
csrf = call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login", method="POST", expected=200,
     body={"username": username, "password": password}, form=True)
csrf = call("/api/v1/auth/csrf")["token"]
viewer = call("/api/v1/auth/me")
assert viewer["isOwner"] is True, viewer
account = post("/api/v1/seller-accounts", {"displayName": "V65-Local-Only", "region": "NA"},
               expected=201, headers={"Idempotency-Key": "v65-smoke-local-account"})
marketplace = post(f"/api/v1/seller-accounts/{account['id']}/marketplaces",
                   {"marketplaceId": "ATVPDKIKX0DER"}, expected=201)
mp = marketplace["id"]
post(f"/api/v1/demo/sandbox/{mp}/replay", {"simulateFailure": False},
     expected=201, headers={"Idempotency-Key": "v65-smoke-fixture"})
listings = call(f"/api/v1/seller-marketplaces/{mp}/listings")
assert len(listings) >= 1 and all(l["dataOrigin"] == "MOCK" for l in listings)
sku = listings[0]["sellerSku"]

# V65 maker/checker: activate a SECOND session Owner using local one-use invitation.
# The disposable test environment enables local team invitations only for this CI.
member = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
member_csrf = None


def member_call(path, method="GET", body=None, expected=200, form=False):
    global member_csrf
    headers = {"Accept": "application/json"}
    if member_csrf:
        headers["X-CSRF-TOKEN"] = member_csrf
    data = None
    if body is not None:
        if form:
            data = urlencode(body).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
    req = Request(base + path, data=data, headers=headers, method=method)
    try:
        with member.open(req, timeout=20) as response:
            status, payload = response.status, response.read()
    except HTTPError as error:
        status, payload = error.code, error.read()
    assert status == expected, f"Second Owner {path}: expected {expected}, got {status}: {payload[:180]!r}"
    try:
        return json.loads(payload)
    except ValueError:
        return payload


invite = post("/api/v1/team/invitations", {"loginName": "v65-ci-second-owner"})
member_csrf = member_call("/api/v1/auth/csrf")["token"]
member_password = "v65-disposable-second-owner-strong-password"
member_call("/api/v1/auth/invitations/accept", method="POST",
            body={"token": invite["token"], "password": member_password}, expected=204)
member_csrf = member_call("/api/v1/auth/csrf")["token"]
member_call("/api/v1/auth/login", method="POST",
            body={"username": "v65-ci-second-owner", "password": member_password},
            form=True)
member_csrf = member_call("/api/v1/auth/csrf")["token"]
member_viewer = member_call("/api/v1/auth/me")
assert member_viewer["isOwner"] is True
assert member_viewer["userId"] != viewer["userId"]

route = f"/api/v1/fba/marketplaces/{mp}/shipments"
assert call(route) == []
assert call(route + "/in-transit") == []
foreign_marketplace = str(uuid4())
call(f"/api/v1/fba/marketplaces/{foreign_marketplace}/shipments", expected=404)

def create(reference,quantity=10):
    payload = {
        "requestKey": str(uuid4()), "reference": reference,
        "origin": "MOCK", "lines": [{"sellerSku": sku, "quantity": quantity}]
    }
    response = post(route, payload)
    assert response["shipment"]["state"] == "DRAFT"
    assert response["amazonVerified"] is False and response["fbaStockCredited"] is False
    assert response["lines"][0]["estimatedInTransit"] is None
    assert response["lines"][0]["plannedQuantity"] == quantity
    return response, payload

first, original = create("V65-RECEIPT-MATCH")
id1 = first["shipment"]["id"]
assert post(route, original)["shipment"]["id"] == id1
post(route, {**original, "reference": "V65-MODIFIED"}, expected=409)
post(route, {**original, "requestKey": str(uuid4())}, expected=409)
post(route, {**original, "requestKey": str(uuid4()), "origin": "LIVE",
             "reference": "V65-INVALID-ORIGIN"}, expected=400)
post(route, {**original, "requestKey": str(uuid4()), "reference": "V65-WRONG-SKU",
             "lines": [{"sellerSku": "UNKNOWN-MP-SKU", "quantity": 2}]}, expected=400)
call(f"/api/v1/fba/marketplaces/{foreign_marketplace}/shipments/{id1}", expected=404)
first = post(route + "/" + id1 + "/dispatch")
assert first["shipment"]["state"] == "IN_TRANSIT"
assert first["lines"][0]["estimatedInTransit"] == 10
assert post(route + "/" + id1 + "/dispatch")["shipment"]["state"] == "IN_TRANSIT"
assert call(route + "/in-transit")[0]["estimatedInTransit"] == 10

def add_receipt(shipment_id, quantity, evidence, key=None, expected=200):
    return post(route + "/" + shipment_id + "/receipts",
                {"requestKey": key or str(uuid4()), "sellerSku": sku,
                 "quantity": quantity, "evidenceReference": evidence,
                 "origin": "MOCK"}, expected=expected)

key = str(uuid4())
first = add_receipt(id1, 4, "MOCK-BOX-1", key)
assert first["shipment"]["state"] == "RECEIVING"
assert first["lines"][0]["reportedReceived"] == 4
assert add_receipt(id1, 4, "MOCK-BOX-1", key)["lines"][0]["reportedReceived"] == 4
add_receipt(id1, 5, "MOCK-BOX-1", key, expected=409)
add_receipt(id1, 7, "MOCK-BOX-OVER-LIMIT", expected=200)
# 4 + 7 = 11: positive over-receipt, counts are still only local reports.
assert call(route + "/" + id1)["lines"][0]["delta"] == 1
assert call(route + "/in-transit")[0]["estimatedInTransit"] == 0
add_receipt(id1, 10, "MOCK-BOX-HUGE", expected=400)  # >2x planned
post(route + "/" + id1 + "/review")
add_receipt(id1, 1, "MOCK-AFTER-FREEZE", expected=409)
post(route + "/" + id1 + "/reconcile", expected=409)
post(route + "/" + id1 + "/variances",
     {"sellerSku": sku, "note": "Same receipt author cannot approve own overage"}, expected=403)
assert call(route + "/" + id1)["shipment"]["state"] == "REVIEWING"
assert call(route + "/in-transit") == []

second, original2 = create("V65-ZERO-DIFFERENCE")
id2 = second["shipment"]["id"]
post(route + "/" + id2 + "/dispatch")
add_receipt(id2, 10, "MOCK-ALL-UNITS")
post(route + "/" + id2 + "/review")
closed = post(route + "/" + id2 + "/reconcile")
assert closed["shipment"]["state"] == "RECONCILED"
assert closed["lines"][0]["delta"] == 0
assert closed["amazonVerified"] is False and closed["fbaStockCredited"] is False
assert post(route + "/" + id2 + "/reconcile")["shipment"]["state"] == "RECONCILED"
add_receipt(id2, 1, "MOCK-TOO-LATE", expected=409)

third, original3 = create("V65-REVIEWED-SHORTAGE")
id3 = third["shipment"]["id"]
post(route + "/" + id3 + "/dispatch")
post(route + "/" + id3 + "/review")
post(route + "/" + id3 + "/reconcile", expected=409)
post(route + "/" + id3 + "/variances",
     {"sellerSku": sku, "note": "Shipment creator cannot self-approve shortages"}, expected=403)
resolved = member_call(route + "/" + id3 + "/variances", method="POST",
                       body={"sellerSku": sku,
                             "note": "Reviewed local packing list; zero received as reported"})
assert resolved["lines"][0]["decision"] == "ACCEPT_VARIANCE"
assert resolved["lines"][0]["delta"] == -10
assert resolved["lines"][0]["reviewNote"].startswith("Reviewed local")
member_call(route + "/" + id3 + "/variances", method="POST",
            body={"sellerSku": sku, "note": "Rewriting immutable decision is forbidden"},
            expected=409)
closed = post(route + "/" + id3 + "/reconcile")
assert closed["shipment"]["state"] == "RECONCILED"
assert closed["lines"][0]["estimatedInTransit"] is None
assert len(call(route)) == 3
assert call(route + "/in-transit") == []
print("PASS: V65 authenticated PG18 + MOCK FBA shipment, idempotency, transit, receipts, freeze, variance, close")
