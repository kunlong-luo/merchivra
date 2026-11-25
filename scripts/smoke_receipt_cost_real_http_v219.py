#!/usr/bin/env python3
"""V219: real Spring Security/Session and PG18 Owner/Staff/tenant/Marketplace GET.

This test inserts synthetic *local* identities/receipts into an isolated
disposable CI PostgreSQL 18 database. It DOES NOT validate real WMS, Amazon,
external charges, landed cost, refunds or profit. No production/data access.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import UUID

BASE = "http://127.0.0.1:8080"
assert os.environ.get("MERCHIVRA_V219_DISPOSABLE_ONLY") == "true"
assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('GITHUB_REPOSITORY') == 'kunlong-luo/merchivra'
assert os.environ.get('GITHUB_REF') == 'refs/heads/main'
assert os.environ.get("MERCHIVRA_SPAPI_ENABLED") == "false"
assert os.environ.get("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
# Not just "disposable flag": require the *exact* isolated CI database and
# synthetic bootstrap; an operator may never point this fixture at production.
for key, expected in {
    "DB_HOST": "127.0.0.1", "DB_PORT": "5432",
    "DB_NAME": "merchivra", "DB_USER": "merchivra",
    "DB_PASSWORD": "ci_disposable_database_password",
    "MERCHIVRA_BOOTSTRAP_USERNAME": "v65-ci-owner",
}.items():
    assert os.environ.get(key) == expected, f"V219 requires CI-only {key}"
password = os.environ.get("MERCHIVRA_BOOTSTRAP_PASSWORD")
assert password == "ci-disposable-v65-only-secret-0000"
assert os.environ.get("PGPASSWORD") == "ci_disposable_database_password"


def ident(n):
    return f"00000000-0000-4000-8000-{n:012x}"


tenant_b = ident(21901)
staff_id = ident(21902)
account_a, mp_a, mp_a2 = map(ident, (21903, 21904, 21905))
account_b, mp_b = map(ident, (21906, 21907))
supplier_a, sku_a, order_a, line_a, receipt_a = map(
    ident, (21908, 21909, 21910, 21911, 21912))
supplier_b, sku_b, order_b, line_b, receipt_b = map(
    ident, (21913, 21914, 21915, 21916, 21917))
role_a, role_b = map(ident, (21918, 21919))
staff_login = "v219-disposable-scoped-staff"
all_ids = [tenant_b, staff_id, account_a, mp_a, mp_a2, account_b, mp_b,
           supplier_a, sku_a, order_a, line_a, receipt_a,
           supplier_b, sku_b, order_b, line_b, receipt_b, role_a, role_b]
assert len(set(all_ids)) == len(all_ids)

sql = f"""
BEGIN;
DO $v219$
DECLARE
 a uuid;
 owner_a uuid;
 credential_hash text;
BEGIN
 IF current_setting('server_version_num')::int < 180000 OR
    NOT EXISTS (SELECT 1 FROM flyway_schema_history
                WHERE version='112' AND success) THEN
    RAISE EXCEPTION 'V219 requires PostgreSQL 18 with Flyway V112';
 END IF;
 SELECT m.tenant_id, m.user_id, c.password_hash
 INTO STRICT a, owner_a, credential_hash
 FROM membership m JOIN local_credential c ON c.user_id=m.user_id
 JOIN role_binding rb ON rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
 WHERE c.login_name='v65-ci-owner'
   AND rb.role_code='OWNER' AND rb.seller_account_id IS NULL
   AND m.status='ACTIVE';
 IF EXISTS (SELECT 1 FROM tenant WHERE id='{tenant_b}')
    OR EXISTS (SELECT 1 FROM app_user WHERE id='{staff_id}')
    OR EXISTS (SELECT 1 FROM local_credential WHERE login_name='{staff_login}')
    OR EXISTS (SELECT 1 FROM seller_marketplace WHERE id IN ('{mp_a}','{mp_b}'))
    OR EXISTS (SELECT 1 FROM erp_purchase_receipt WHERE id IN ('{receipt_a}','{receipt_b}')) THEN
    RAISE EXCEPTION 'V219 disposable identity or receipt fixture collision';
 END IF;
 INSERT INTO tenant(id,name) VALUES
  ('{tenant_b}','V219 DISPOSABLE FOREIGN TENANT');
 INSERT INTO app_user(id,identity_ref) VALUES
  ('{staff_id}','local:v219-disposable-scoped-staff');
 -- Test-only account uses the identical ephemeral CI bootstrap password
 -- hash. Never log/print or persist a plaintext credential.
 INSERT INTO local_credential(user_id,login_name,password_hash) VALUES
  ('{staff_id}','{staff_login}',credential_hash);
 INSERT INTO membership(tenant_id,user_id) VALUES
  (a,'{staff_id}'),('{tenant_b}','{staff_id}');
 INSERT INTO seller_account(id,tenant_id,seller_id,region) VALUES
  ('{account_a}',a,'V219-DISPOSABLE-A','NA'),
  ('{account_b}','{tenant_b}','V219-DISPOSABLE-B','NA');
 INSERT INTO seller_marketplace(id,tenant_id,seller_account_id,
   marketplace_id,currency_code,timezone) VALUES
  ('{mp_a}',a,'{account_a}','V219-A1','USD','Etc/UTC'),
  ('{mp_a2}',a,'{account_a}','V219-A2','USD','Etc/UTC'),
  ('{mp_b}','{tenant_b}','{account_b}','V219-B1','EUR','Etc/UTC');
 -- Staff has OPERATOR_READ *only* in tenant A; the very same principal
 -- has tenant-wide OWNER in tenant B. The active-tenant selection,
 -- not the mere existence of an OWNER role somewhere, must govern GET.
 INSERT INTO role_binding(id,tenant_id,user_id,role_code,seller_account_id)
 VALUES ('{role_a}',a,'{staff_id}','OPERATOR_READ','{account_a}'),
        ('{role_b}','{tenant_b}','{staff_id}','OWNER',NULL);
 INSERT INTO erp_supplier(id,tenant_id,supplier_code,display_name) VALUES
  ('{supplier_a}',a,'V219-A-SUP','V219 Synthetic Supplier A'),
  ('{supplier_b}','{tenant_b}','V219-B-SUP','V219 Synthetic Supplier B');
 INSERT INTO product_sku(id,tenant_id,internal_sku,display_name) VALUES
  ('{sku_a}',a,'V219-A-SKU','V219 Synthetic SKU A'),
  ('{sku_b}','{tenant_b}','V219-B-SKU','V219 Synthetic SKU B');
 INSERT INTO erp_purchase_order
   (id,tenant_id,supplier_id,order_number,currency_code,status,created_by)
 VALUES
  ('{order_a}',a,'{supplier_a}','V219-SYNTHETIC-PO-A','USD','RECEIVING',owner_a),
  ('{order_b}','{tenant_b}','{supplier_b}',
   'V219-SYNTHETIC-PO-B','EUR','RECEIVING','{staff_id}');
 INSERT INTO erp_purchase_order_line
   (id,tenant_id,purchase_order_id,product_sku_id,ordered_quantity,unit_cost)
 VALUES
  ('{line_a}',a,'{order_a}','{sku_a}',4,10),
  ('{line_b}','{tenant_b}','{order_b}','{sku_b}',2,5);
 INSERT INTO erp_purchase_receipt
   (id,tenant_id,purchase_order_id,purchase_order_line_id,quantity,received_by)
 VALUES
  ('{receipt_a}',a,'{order_a}','{line_a}',2,owner_a),
  ('{receipt_b}','{tenant_b}','{order_b}','{line_b}',1,'{staff_id}');
END $v219$;
COMMIT;
"""
psql = subprocess.run(
    ["psql", "-X", "-h", "127.0.0.1", "-U", "merchivra",
     "-d", "merchivra", "-v", "ON_ERROR_STOP=1", "-q"],
    input=sql, text=True, capture_output=True, timeout=45)
if psql.returncode:
    raise SystemExit("V219 disposable tenant/role/receipt fixture failed: " +
                     psql.stderr[-2200:])
# Read the actual owner tenant UUID from PG, do not guess a bootstrap UUID.
p = subprocess.run(
    ["psql", "-X", "-h", "127.0.0.1", "-U", "merchivra", "-d", "merchivra",
     "-v", "ON_ERROR_STOP=1", "-Atc",
     "SELECT m.tenant_id FROM membership m "
     "JOIN local_credential c ON c.user_id=m.user_id "
     "WHERE c.login_name='v65-ci-owner'"],
    text=True, capture_output=True, timeout=10, check=True)
tenant_a = str(UUID(p.stdout.strip()))
assert tenant_a != tenant_b


class Browser:
    """A separate real Spring JSESSIONID/CSRF principal, never shared."""

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.jar))

    def call(self, path, expected, method="GET", body=None, form=False,
             csrf=None):
        assert path.startswith("/") and not path.startswith("//")
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            headers["Content-Type"] = ("application/x-www-form-urlencoded"
                                       if form else "application/json")
            data = (urlencode(body) if form else json.dumps(body)).encode()
        if csrf:
            headers["X-CSRF-TOKEN"] = csrf
        try:
            with self.opener.open(
                Request(BASE + path, headers=headers, data=data, method=method),
                timeout=10,
            ) as res:
                code, raw, response_headers = (
                    res.status, res.read(), res.headers)
        except HTTPError as e:
            code, raw, response_headers = e.code, e.read(), e.headers
        assert code == expected, (path, "HTTP", code, "expected", expected,
                                   raw[:180])
        # Spring can legitimately return an empty/plain-text security 401/403.
        # Assert the actual HTTP code, do not require the error body to be JSON.
        if not raw:
            payload = None
        elif response_headers.get_content_type() == "application/json":
            payload = json.loads(raw)
        elif expected == 200:
            raise AssertionError("Expected JSON from Spring business endpoint")
        else:
            payload = None
        return payload, response_headers

    def login(self, username):
        csrf, _ = self.call("/api/v1/auth/csrf", 200)
        assert csrf["headerName"] == "X-CSRF-TOKEN"
        self.call("/api/v1/auth/login", 200, "POST",
                  {"username": username, "password": password},
                  form=True, csrf=csrf["token"])
        viewer, headers = self.call("/api/v1/auth/me", 200)
        assert "no-store" in headers.get("Cache-Control", "").lower()
        assert len([c for c in self.jar if c.name == "JSESSIONID"]) == 1
        return viewer

    def switch(self, tenant, expected=200):
        csrf, _ = self.call("/api/v1/auth/csrf", 200)
        return self.call("/api/v1/auth/active-tenant", expected, "POST",
                         {"tenantId": tenant}, csrf=csrf["token"])

    def logout(self):
        csrf, _ = self.call("/api/v1/auth/csrf", 200)
        self.call("/api/v1/auth/logout", 204, "POST", csrf=csrf["token"])


def route(mp, receipt):
    return ("/api/v1/finance/marketplaces/" + mp +
            "/purchase-receipts/" + receipt + "/manual-cost-integrity")


def unknown(response, headers, mp, receipt):
    assert response["marketplaceId"] == mp, "leaked another Marketplace ID"
    assert response["purchaseReceiptId"] == receipt, "leaked another receipt"
    assert response["localPlanCount"] == 0 and response["plans"] == []
    assert "NO_LOCAL_RECEIPT_COST_PROPOSAL_UNKNOWN_NOT_ZERO" in response[
        "blockerCodes"]
    assert response["completeExternalCostCoverage"] is False
    assert response["receiptLandedCostVerified"] is False
    assert response["actualBatchCogsVerified"] is False
    assert response["actualBatchCogs"] is None
    assert response["postingAllowed"] is False
    assert response["realizedNetProfit"] is None
    assert "no-store" in headers.get("Cache-Control", "").lower()
    assert headers.get("X-Content-Type-Options", "").lower() == "nosniff"


anon = Browser()
anon.call(route(mp_a, receipt_a), 401)
anon.call(route(mp_b, receipt_b), 401)
owner = Browser()
v = owner.login("v65-ci-owner")
assert v["isOwner"] is True and v["activeTenantId"] == tenant_a
a, h = owner.call(route(mp_a, receipt_a), 200)
unknown(a, h, mp_a, receipt_a)
# A V20 receipt belongs to the tenant, not a unique Marketplace: both
# SAME-tenant marketplaces have a valid local Owner read, but no proof of cost.
a2, h = owner.call(route(mp_a2, receipt_a), 200)
unknown(a2, h, mp_a2, receipt_a)
# URL query tenant spoofing has no influence; server session still determines A.
spoof, h = owner.call(route(mp_a, receipt_a) + "?tenantId=" + tenant_b, 200)
unknown(spoof, h, mp_a, receipt_a)
# Owner on A never acquires B resources by possessing UUIDs.
owner.call(route(mp_b, receipt_a), 404)
owner.call(route(mp_b, receipt_b), 404)
owner.call(route(mp_a, receipt_b), 404)
owner.call(route(mp_a, ident(21999)), 404)
owner.switch(tenant_b, expected=403)
assert owner.call("/api/v1/auth/me", 200)[0]["activeTenantId"] == tenant_a

scoped = Browser()
v = scoped.login(staff_login)
assert tenant_a in v["availableTenantIds"] and tenant_b in v["availableTenantIds"]
scoped.switch(tenant_a, 200)
v, _ = scoped.call("/api/v1/auth/me", 200)
assert v["activeTenantId"] == tenant_a and v["isOwner"] is False
# This is a genuine authenticated OPERATOR_READ in A, NOT a mock 403.
scoped.call(route(mp_a, receipt_a), 403)
scoped.call(route(mp_a2, receipt_a), 403)
scoped.call(route(mp_b, receipt_b), 403)
# The same user is an Owner in B, but B cannot access A even by known UUID.
scoped.switch(tenant_b, 200)
v, _ = scoped.call("/api/v1/auth/me", 200)
assert v["activeTenantId"] == tenant_b and v["isOwner"] is True
b, h = scoped.call(route(mp_b, receipt_b), 200)
unknown(b, h, mp_b, receipt_b)
scoped.call(route(mp_a, receipt_a), 404)
scoped.call(route(mp_a, receipt_b), 404)
# Revisiting staff A must re-enable the Owner-only denial.
scoped.switch(tenant_a, 200)
scoped.call(route(mp_a, receipt_a), 403)
scoped.switch(tenant_b, 200)

# A logout invalidates A's session, never another user's B session.
owner.logout()
owner.call(route(mp_a, receipt_a), 401)
still_active, headers = scoped.call(route(mp_b, receipt_b), 200)
unknown(still_active, headers, mp_b, receipt_b)
scoped.logout()
scoped.call(route(mp_b, receipt_b), 401)
anon.call(route(mp_a, receipt_a), 401)

print("PASS V219 real Spring/PG18 HTTP: separate JSESSIONID login, "
      "OWNER A 200 UNKNOWN and no-store, same-tenant Marketplace read, "
      "query-tenant spoof ignored, tenant B marketplace/receipt 404, "
      "authenticated scoped OPERATOR_READ A 403 even with OWNER B, "
      "active-tenant switch 403/200, session logout 401 and no cross-user "
      "session leakage (synthetic disposable only; no external COGS)")


# V252: use the same actual principal/tenant fixture, not a second login stack.
# At V219 there are no sale-range claims, so the only valid result is HOLD.
def composition_route(mp, receipt):
    return route(mp, receipt).replace("/manual-cost-integrity", "/cost-composition-candidate")


def composition_unknown(payload, headers, mp, receipt):
    assert payload["marketplaceId"] == mp
    assert payload["purchaseReceiptId"] == receipt
    assert payload["state"] == "LOCAL_COST_COMPOSITION_HOLD"
    assert "RECEIPT_CLAIM_POPULATION_MISSING_OR_UNBOUNDED" in payload["blockerCodes"]
    for key in ("originalCurrency", "receiptCandidateCost", "allocatedCandidateCost",
                "unallocatedCandidateCost", "actualBatchCogs", "realizedNetProfit"):
        assert key in payload and payload[key] is None
    assert payload["components"] == [] and payload["saleShares"] == []
    for key in ("completeExternalCostCoverage", "actualBatchCogsVerified", "postingAllowed"):
        assert payload[key] is False
    assert "no-store" in headers.get("Cache-Control", "").lower()


anon.call(composition_route(mp_a, receipt_a), 401)
owner.login("v65-ci-owner")
p, h = owner.call(composition_route(mp_a, receipt_a), 200)
composition_unknown(p, h, mp_a, receipt_a)
p, h = owner.call(composition_route(mp_a, receipt_a) + "?tenantId=" + tenant_b, 200)
composition_unknown(p, h, mp_a, receipt_a)
owner.call(composition_route(mp_b, receipt_b), 404)
owner.call(composition_route(mp_a, receipt_b), 404)
owner.call(composition_route(mp_a, ident(21999)), 404)
scoped.login(staff_login)
scoped.switch(tenant_a, 200)
scoped.call(composition_route(mp_a, receipt_a), 403)
scoped.switch(tenant_b, 200)
p, h = scoped.call(composition_route(mp_b, receipt_b), 200)
composition_unknown(p, h, mp_b, receipt_b)
scoped.call(composition_route(mp_a, receipt_a), 404)
owner.logout()
owner.call(composition_route(mp_a, receipt_a), 401)
scoped.logout()
print("PASS V252 actual Owner HTTP candidate HOLD/no-store/null amounts, anonymous/staff/foreign tenant denied")
