#!/usr/bin/env python3
"""V220 real Spring Session/PG18 read of V112 nonempty *manual* cost proposals.

Only runs after V219 in disposable main GitHub Actions CI. The amounts,
document digests, reviewer decisions and V165 collision are SYNTHETIC LOCAL
METADATA. This is NOT external landed cost, Amazon billing, true COGS or profit.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, HTTPCookieProcessor, build_opener
from uuid import UUID

HOST = "http://127.0.0.1:8080"
for key, value in {
    "MERCHIVRA_V220_DISPOSABLE_ONLY": "true",
    "GITHUB_ACTIONS": "true",
    "GITHUB_REPOSITORY": "kunlong-luo/merchivra",
    "GITHUB_REF": "refs/heads/main",
    "MERCHIVRA_SPAPI_ENABLED": "false",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED": "false",
    "DB_HOST": "127.0.0.1", "DB_PORT": "5432", "DB_NAME": "merchivra",
    "DB_USER": "merchivra", "DB_PASSWORD": "ci_disposable_database_password",
    "PGPASSWORD": "ci_disposable_database_password",
    "MERCHIVRA_BOOTSTRAP_USERNAME": "v65-ci-owner",
    "MERCHIVRA_BOOTSTRAP_PASSWORD": "ci-disposable-v65-only-secret-0000",
}.items():
    assert os.environ.get(key) == value, "V220 requires disposable main CI: " + key
assert os.environ.get("GITHUB_RUN_ID", "").isdigit()


def uid(n):
    return f"00000000-0000-4000-8000-{n:012x}"


# Actual V219 fixture identities: no fuzzy SKU/date/Marketplace inference.
mp_a, mp_a2, mp_b = (uid(21904), uid(21905), uid(21907))
staff_id, order_a, line_a = (uid(21902), uid(21910), uid(21911))
receipt_a, receipt_b = (uid(21912), uid(21917))
extra_receipt = uid(22099)
other_same_document = uid(220400)
other_marketplace_document = uid(220401)
owner_login = "v65-ci-owner"
staff_login = "v219-disposable-scoped-staff"
password = os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]


def sql_run(statement, want_result=False):
    args = ["psql", "-X", "-h", "127.0.0.1", "-U", "merchivra",
            "-d", "merchivra", "-v", "ON_ERROR_STOP=1", "-At"]
    p = subprocess.run(args, input=statement, text=True, capture_output=True,
                       timeout=45)
    if p.returncode:
        raise AssertionError("V220 isolated PG18 fixture failed: " +
                             p.stderr[-2400:])
    return p.stdout.strip() if want_result else None


tenant_a = str(UUID(sql_run("""
SELECT m.tenant_id FROM membership m
JOIN local_credential c ON c.user_id=m.user_id
JOIN role_binding r ON r.tenant_id=m.tenant_id AND r.user_id=m.user_id
WHERE c.login_name='v65-ci-owner' AND r.role_code='OWNER'
  AND r.seller_account_id IS NULL AND m.status='ACTIVE'
""", want_result=True)))
# SQL-side, before any writes: validate installed real Flyway, V219 fixture,
# exact actor/membership and that this V220 receipt is still without claims.
sql_run(f"""
DO $check$
DECLARE a uuid;
BEGIN
 SELECT m.tenant_id INTO STRICT a FROM membership m
 JOIN local_credential c ON c.user_id=m.user_id
 WHERE c.login_name='{owner_login}';
 IF current_setting('server_version_num')::int<180000 OR
    NOT EXISTS(SELECT 1 FROM flyway_schema_history
               WHERE version='112' AND success) THEN
   RAISE EXCEPTION 'V220 requires PG18 and exact Flyway V112';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM membership
    WHERE user_id='{staff_id}' AND tenant_id=a AND status='ACTIVE')
    OR NOT EXISTS(SELECT 1 FROM role_binding
      WHERE user_id='{staff_id}' AND tenant_id=a
        AND role_code='OPERATOR_READ' AND seller_account_id IS NOT NULL)
    OR NOT EXISTS(SELECT 1 FROM seller_marketplace
      WHERE tenant_id=a AND id='{mp_a}')
    OR NOT EXISTS(SELECT 1 FROM erp_purchase_receipt
      WHERE tenant_id=a AND id='{receipt_a}' AND
            purchase_order_id='{order_a}')
    OR EXISTS(SELECT 1 FROM finance_receipt_cost_line_v112
      WHERE tenant_id=a AND receipt_id='{receipt_a}')
    OR EXISTS(SELECT 1 FROM finance_cost_source_v91
      WHERE tenant_id=a AND id IN ('{other_same_document}',
                                    '{other_marketplace_document}'))
    OR EXISTS(SELECT 1 FROM erp_purchase_receipt WHERE id='{extra_receipt}')
 THEN
   RAISE EXCEPTION 'V220 requires exact untouched synthetic V219 base';
 END IF;
END $check$;
""")


class Browser:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.cookies))

    def call(self, path, expected=200, method="GET", body=None,
             csrf=None, form=False):
        assert path.startswith("/") and not path.startswith("//")
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            headers["Content-Type"] = ("application/x-www-form-urlencoded" if
                                       form else "application/json")
            data = (urlencode(body) if form else json.dumps(body)).encode()
        if csrf:
            headers["X-CSRF-TOKEN"] = csrf
        try:
            with self.opener.open(
                Request(HOST + path, headers=headers, data=data, method=method),
                timeout=12,
            ) as r:
                status, raw, h = r.status, r.read(), r.headers
        except HTTPError as e:
            status, raw, h = e.code, e.read(), e.headers
        assert status == expected, (path, status, expected, raw[:150])
        payload = json.loads(raw) if (raw and
            h.get_content_type() == "application/json") else None
        return payload, h, raw

    def login(self, username):
        csrf, _, _ = self.call("/api/v1/auth/csrf")
        self.call("/api/v1/auth/login", method="POST", body={
            "username": username, "password": password
        }, form=True, csrf=csrf["token"])
        viewer, _, _ = self.call("/api/v1/auth/me")
        assert len([c for c in self.cookies if c.name == "JSESSIONID"]) == 1
        return viewer

    def switch(self, tenant):
        csrf, _, _ = self.call("/api/v1/auth/csrf")
        self.call("/api/v1/auth/active-tenant", method="POST",
                  body={"tenantId": tenant}, csrf=csrf["token"])


def path(mp):
    return ("/api/v1/finance/marketplaces/" + mp +
            "/purchase-receipts/" + receipt_a + "/manual-cost-integrity")


def assert_local(response, headers, count):
    assert response["marketplaceId"] == mp_a
    assert response["purchaseReceiptId"] == receipt_a
    assert response["ruleVersion"] == "V216_RECEIPT_V112_V91_V92_V165_READ_GATE_V1"
    assert response["localPlanCount"] == count
    assert len(response["plans"]) == count and count <= 50
    assert len({x["planId"] for x in response["plans"]}) == count
    assert len({x["sourceId"] for x in response["plans"]}) == count
    assert "V112_LOCAL_MANUAL_PROPOSAL_IS_NOT_EXTERNAL_LANDED_COST" in response[
        "blockerCodes"]
    assert "G1_CAPITALIZATION_FX_AND_REFUND_POLICY_NOT_APPROVED" in response[
        "blockerCodes"]
    assert response["completeExternalCostCoverage"] is False
    assert response["receiptLandedCostVerified"] is False
    assert response["actualBatchCogsVerified"] is False
    assert response["actualBatchCogs"] is None
    assert response["postingAllowed"] is False
    assert response["realizedNetProfit"] is None
    assert all(x["independentlyVerifiedLandedCost"] is False and
               x["actualLandedCost"] is None for x in response["plans"])
    assert "no-store" in headers.get("Cache-Control", "").lower()
    assert headers.get("X-Content-Type-Options", "").lower() == "nosniff"


def plan(number, response):
    return next(x for x in response["plans"]
                if x["planId"] == uid(220200 + number))


def make_plans(start, stop):
    """The real V91/V112 triggers and audit tables, never mock TEMP views."""
    assert 1 <= start <= stop <= 51
    return f"""
DO $v220$
DECLARE
 a uuid := '{tenant_a}';
 owner uuid;
 n integer;
 src uuid;
 pl uuid;
 total numeric;
 line_amount numeric;
 digest text;
BEGIN
 SELECT c.user_id INTO STRICT owner FROM local_credential c
 WHERE c.login_name='{owner_login}';
 FOR n IN {start}..{stop} LOOP
   src:=('00000000-0000-4000-8000-' ||
      lpad(to_hex(220100+n),12,'0'))::uuid;
   pl:=('00000000-0000-4000-8000-' ||
      lpad(to_hex(220200+n),12,'0'))::uuid;
   total:=CASE WHEN n=1 THEN 100 WHEN n=2 THEN 30 ELSE 1 END;
   line_amount:=CASE WHEN n=1 THEN 40 WHEN n=2 THEN 10 ELSE 1 END;
   digest:=CASE WHEN n=1 THEN repeat('a',64)
     WHEN n=2 THEN repeat('b',64)
     ELSE lpad(to_hex(220000+n),64,'0') END;
   INSERT INTO finance_cost_source_v91
     (id,tenant_id,seller_marketplace_id,category,source_kind,
      source_key,source_document_sha256,business_date,currency_code,
      amount,created_by)
   VALUES (src,a,'{mp_a}',
     CASE WHEN n=2 THEN 'IMPORT_DUTY' ELSE 'INBOUND_FREIGHT' END,
     'MANUAL_INVOICE','V220-SYNTHETIC-EXPENSE-' || n,digest,
     DATE '2026-11-24','USD',total,'{staff_id}');
   UPDATE finance_cost_source_v91
     SET review_state='REVIEWED_UNVERIFIED',
       reviewed_by=owner,reviewed_at=now(),
       review_reason='V220 isolated synthetic metadata review only'
     WHERE tenant_id=a AND id=src;
   INSERT INTO finance_cost_source_audit_v91
     (id,tenant_id,source_id,action,actor_id)
   VALUES(gen_random_uuid(),a,src,'STAGED','{staff_id}'),
         (gen_random_uuid(),a,src,'REVIEWED_UNVERIFIED',owner);
   INSERT INTO finance_receipt_cost_plan_v112
     (id,tenant_id,source_id,proposed_by)
   VALUES(pl,a,src,'{staff_id}');
   INSERT INTO finance_receipt_cost_line_v112
     (tenant_id,plan_id,receipt_id,amount)
   VALUES(a,pl,'{receipt_a}',line_amount);
   IF n=1 THEN
     INSERT INTO finance_receipt_cost_line_v112
       (tenant_id,plan_id,receipt_id,amount)
     VALUES(a,pl,'{extra_receipt}',50.000000);
   END IF;
   UPDATE finance_receipt_cost_plan_v112
    SET state='APPROVED_UNVERIFIED',reviewed_by=owner,
      reviewed_at=now(),
      review_reason='V220 independent synthetic allocation decision'
    WHERE tenant_id=a AND id=pl;
 END LOOP;
END $v220$;
"""


# Synthetic extra receipt has same real V20 purchase line / original currency.
sql_run(f"""
INSERT INTO erp_purchase_receipt
 (id,tenant_id,purchase_order_id,purchase_order_line_id,quantity,received_by)
SELECT '{extra_receipt}',r.tenant_id,r.purchase_order_id,
 r.purchase_order_line_id,1,r.received_by
FROM erp_purchase_receipt r WHERE r.tenant_id='{tenant_a}'
 AND r.id='{receipt_a}';
""")
sql_run(make_plans(1, 2))

# Same-tenant manual source with identical digest creates a V165 *signal*,
# not an externally verified duplicate charge. Foreign Marketplace matching
# digest must NOT contaminate the source's own scoped signal population.
sql_run(f"""
DO $v220$
DECLARE actor uuid;
BEGIN
 SELECT user_id INTO STRICT actor FROM local_credential
 WHERE login_name='{owner_login}';
 INSERT INTO finance_cost_source_v91
  (id,tenant_id,seller_marketplace_id,category,source_kind,
   source_key,source_document_sha256,business_date,currency_code,
   amount,created_by)
 VALUES
 ('{other_same_document}','{tenant_a}','{mp_a}','IMPORT_DUTY',
  'MANUAL_OTHER','V220-SYNTHETIC-SAME-DIGEST',repeat('b',64),
  DATE '2026-11-24','USD',13.000000,'{staff_id}'),
 ('{other_marketplace_document}','{tenant_a}','{mp_a2}','INBOUND_FREIGHT',
  'MANUAL_OTHER','V220-SYNTHETIC-OTHER-MP',repeat('a',64),
  DATE '2026-11-24','USD',15.000000,'{staff_id}');
END $v220$;
""")

owner = Browser()
viewer = owner.login(owner_login)
assert viewer["isOwner"] is True and viewer["activeTenantId"] == tenant_a
value, headers, _ = owner.call(path(mp_a))
assert_local(value, headers, 2)
clean, collided = plan(1, value), plan(2, value)
assert clean["originalCurrency"] == "USD"
assert clean["localReceiptCandidateAmount"] == "40.000000"
assert clean["localAllReceiptsCandidateAmount"] == "90.000000"
assert clean["globalReceiptCount"] == 2
assert clean["integrityState"] == "LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED"
assert clean["crossSourceIdentitySignalCount"] == 0
assert collided["originalCurrency"] == "USD"
assert collided["localReceiptCandidateAmount"] == "10.000000"
assert collided["localAllReceiptsCandidateAmount"] == "10.000000"
assert collided["crossSourceIdentitySignalCount"] >= 1
assert collided["integrityState"] == "POTENTIAL_EXTERNAL_CHARGE_IDENTITY_COLLISION_HOLD"
assert "V165_CROSS_SOURCE_POTENTIAL_COLLISION_HOLD" in value["blockerCodes"]
# B's completely unrelated receipts and same-tenant B Marketplace must not
# receive the source's amounts even if a document digest happens to match.
empty, h, _ = owner.call(path(mp_a2))
assert empty["localPlanCount"] == 0 and empty["plans"] == []
assert "NO_LOCAL_RECEIPT_COST_PROPOSAL_UNKNOWN_NOT_ZERO" in empty["blockerCodes"]
assert empty["actualBatchCogs"] is None and empty["realizedNetProfit"] is None
assert "no-store" in h.get("Cache-Control", "").lower()
owner.call(path(mp_b), expected=404)
owner.call("/api/v1/finance/marketplaces/" + mp_a +
           "/purchase-receipts/" + receipt_b + "/manual-cost-integrity",
           expected=404)

# Real V92 trigger: cannot reuse an already claimed V91 source.
# Catch only the exact expected database guard error; a different SQL error
# (or accidentally accepted competing plan) MUST fail CI.
sql_run(f"""
DO $v220$
BEGIN
 BEGIN
  INSERT INTO finance_cost_allocation_batch_v92
   (id,tenant_id,source_id,request_key,target_kind,proposed_by)
  VALUES ('{uid(220500)}','{tenant_a}','{uid(220101)}',
    '{uid(220501)}','PERIOD','{staff_id}');
  RAISE EXCEPTION 'V220 unexpectedly accepted duplicate V92 attribution';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM NOT LIKE '%already belongs to active receipt proposal%' THEN
   RAISE;
  END IF;
 END;
END $v220$;
""")
post, h, _ = owner.call(path(mp_a))
assert_local(post, h, 2)
assert plan(1, post)["integrityState"] == "LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED"

# A real PO currency change invalidates BOTH locally approved original
# currency plans at READ time, without editing immutable V112/91 identities.
sql_run(f"""UPDATE erp_purchase_order SET currency_code='EUR'
 WHERE tenant_id='{tenant_a}' AND id='{order_a}';""")
drift, h, _ = owner.call(path(mp_a))
assert_local(drift, h, 2)
assert all(x["integrityState"] == "LOCAL_RECEIPT_EVIDENCE_STALE_OR_UNAUDITED"
           for x in drift["plans"])
assert "RECEIPT_LOCAL_SOURCE_AMOUNT_CURRENCY_OR_V92_DRIFT" in drift[
    "blockerCodes"]
sql_run(f"""UPDATE erp_purchase_order SET currency_code='USD'
 WHERE tenant_id='{tenant_a}' AND id='{order_a}';""")
restored, h, _ = owner.call(path(mp_a))
assert_local(restored, h, 2)
assert plan(1, restored)["integrityState"] == (
    "LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED")

# V244: insert an extra audit event on a disposable source with the real
# schema and a real logged-in Owner. Keep the anomaly append-only, rather
# than deleting it to make subsequent tests pass.
sql_run(f"""
INSERT INTO finance_cost_source_audit_v91
 (id,tenant_id,source_id,action,actor_id)
VALUES (gen_random_uuid(),'{tenant_a}','{uid(220101)}','STAGED','{staff_id}');
""")
extra, h, _ = owner.call(path(mp_a))
assert_local(extra, h, 2)
assert plan(1, extra)["integrityState"] == "LOCAL_RECEIPT_EVIDENCE_STALE_OR_UNAUDITED"
assert "SOURCE_OR_INDEPENDENT_DECISION_AUDIT_MISSING" in plan(1, extra)["blockerCodes"]
assert plan(1, extra)["localAllReceiptsCandidateAmount"] == "90.000000"
assert plan(2, extra)["integrityState"] == "POTENTIAL_EXTERNAL_CHARGE_IDENTITY_COLLISION_HOLD"
print("PASS V244 real Owner HTTP duplicate source audit holds original receipt cost")

# Real global cap: exactly 50 must be complete/200, 51 must be 409.
# A 409 may NEVER be a partial 50-item "successful" receipt snapshot.
sql_run(make_plans(3, 50))
bounded, h, _ = owner.call(path(mp_a))
assert_local(bounded, h, 50)
assert plan(1, bounded)["localAllReceiptsCandidateAmount"] == "90.000000"
assert plan(2, bounded)["crossSourceIdentitySignalCount"] >= 1
sql_run(make_plans(51, 51))
for _ in range(2):
    rejected, h, body = owner.call(path(mp_a), expected=409)
    assert not rejected or rejected.get("localPlanCount") is None
    assert b'"plans"' not in body and b'"actualBatchCogs"' not in body

# 51 source rows in MP A cannot bleed through a different Marketplace or
# elevate A's staff privileges. B Owner in a separate real Session remains B.
empty, h, _ = owner.call(path(mp_a2))
assert empty["localPlanCount"] == 0 and empty["plans"] == []
staff = Browser()
v = staff.login(staff_login)
staff.switch(tenant_a)
v, _, _ = staff.call("/api/v1/auth/me")
assert v["isOwner"] is False and v["activeTenantId"] == tenant_a
staff.call(path(mp_a), expected=403)
staff.switch(uid(21901))
v, _, _ = staff.call("/api/v1/auth/me")
assert v["isOwner"] is True
staff.call(path(mp_a), expected=404)
owner.call(path(mp_a), expected=409)
anon = Browser()
anon.call(path(mp_a), expected=401)

print("PASS V220 real Spring/PG18 V112 nonempty Owner HTTP: approved "
      "unverified original-currency 40/90 receipt/global sums, independent "
      "V91/V112 audit, V165 same-Marketplace collision but foreign "
      "Marketplace excluded, V92 duplicate attribution rejected by DB, "
      "PO currency drift read-time hold restored, 50 complete GET 200 vs "
      "51 source plans GET 409 with no partial COGS, A staff 403 and B "
      "Owner cross-tenant 404, actual COGS/profit always null (CI only)")
