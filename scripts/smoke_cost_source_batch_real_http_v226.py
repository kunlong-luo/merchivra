#!/usr/bin/env python3
"""V226: real Spring Owner HTTP + PG18 bounded V91 manual batch staging.

Disposable CI main ONLY. No real Amazon/Ads/bank credentials, invoices,
verified source evidence, inventory/COGS posting or realized profit.
"""
import http.cookiejar
import json
import os
import subprocess
import urllib.parse
import urllib.request
from urllib.error import HTTPError
from uuid import UUID, uuid4

REQUIRED = {
    "MERCHIVRA_V226_DISPOSABLE_ONLY": "true",
    "MERCHIVRA_V225_DISPOSABLE_ONLY": "true",
    "MERCHIVRA_V222_DISPOSABLE_ONLY": "true",
    "SPRING_PROFILES_ACTIVE": "ci-v222",
    "GITHUB_ACTIONS": "true",
    "GITHUB_REPOSITORY": "kunlong-luo/merchivra",
    "GITHUB_REF": "refs/heads/main",
    "MERCHIVRA_SPAPI_ENABLED": "false",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED": "false",
    "DB_HOST": "127.0.0.1", "DB_PORT": "5432",
    "DB_NAME": "merchivra", "DB_USER": "merchivra",
    "DB_PASSWORD": "ci_disposable_database_password",
    "PGPASSWORD": "ci_disposable_database_password",
    "MERCHIVRA_BOOTSTRAP_USERNAME": "v65-ci-owner",
    "MERCHIVRA_BOOTSTRAP_PASSWORD": "ci-disposable-v65-only-secret-0000",
}
for name, expected in REQUIRED.items():
    assert os.environ.get(name) == expected, "V226 requires isolated CI: " + name
assert os.environ.get("GITHUB_RUN_ID", "").isdigit()

PARGS = ["psql", "-X", "-qAt", "-h", "127.0.0.1", "-U", "merchivra",
         "-d", "merchivra", "-v", "ON_ERROR_STOP=1"]


def pg(text, timeout=18):
    r = subprocess.run(PARGS + ["-c", text], capture_output=True, text=True,
                       timeout=timeout)
    if r.returncode:
        raise AssertionError("V226 PG18 check failed: " + r.stderr[-900:])
    return r.stdout.strip()


def num(query):
    return int(pg(query).splitlines()[-1])


TENANT = str(UUID(pg("""
SELECT m.tenant_id FROM membership m
JOIN local_credential c ON c.user_id=m.user_id
JOIN role_binding b ON b.tenant_id=m.tenant_id AND b.user_id=m.user_id
WHERE c.login_name='v65-ci-owner' AND b.role_code='OWNER'
  AND b.seller_account_id IS NULL AND m.status='ACTIVE';
""")))
MP_A = "00000000-0000-4000-8000-000000005590"
MP_A2 = "00000000-0000-4000-8000-000000005591"
MP_B = "00000000-0000-4000-8000-000000005593"
RECEIPT = "00000000-0000-4000-8000-0000000056b9"
pg(f"""
DO $v226$
BEGIN
 IF current_setting('server_version_num')::integer < 180000
    OR NOT EXISTS (SELECT 1 FROM flyway_schema_history
                   WHERE version='115' AND success)
    OR (SELECT count(*) FROM pg_trigger
        WHERE NOT tgisinternal AND tgname LIKE '%_epoch_%_v113') <> 46
    OR (SELECT count(*) FROM seller_marketplace
        WHERE tenant_id='{TENANT}' AND id IN ('{MP_A}','{MP_A2}')) <> 2
    OR NOT EXISTS (SELECT 1 FROM erp_purchase_receipt
                   WHERE tenant_id='{TENANT}' AND id='{RECEIPT}')
 THEN RAISE EXCEPTION 'V226 requires V219-V225 PG18 fixtures and V115';
 END IF;
END $v226$;
""")


def epoch():
    return num("SELECT coalesce((SELECT revision FROM "
               f"finance_receipt_read_epoch_v113 WHERE tenant_id='{TENANT}'),0)")


def audit_count(ids):
    return num("SELECT count(*) FROM finance_cost_source_audit_v91 "
               "WHERE tenant_id='" + TENANT + "' AND source_id IN (" +
               ",".join("'" + x + "'" for x in ids) + ")")


def source_count(ids):
    return num("SELECT count(*) FROM finance_cost_source_v91 "
               "WHERE tenant_id='" + TENANT + "' AND id IN (" +
               ",".join("'" + x + "'" for x in ids) + ")")


class Browser:
    def __init__(self):
        jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(jar))

    def call(self, path, method="GET", body=None, csrf=None):
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        if csrf:
            headers["X-CSRF-TOKEN"] = csrf
        request = urllib.request.Request(
            "http://127.0.0.1:8080" + path, method=method,
            headers=headers, data=data)
        try:
            with self.opener.open(request, timeout=22) as response:
                status, raw, response_headers = (
                    response.status, response.read(), response.headers)
        except HTTPError as exc:
            status, raw, response_headers = exc.code, exc.read(), exc.headers
        payload = (json.loads(raw) if raw and
                   response_headers.get_content_type() == "application/json"
                   else None)
        return status, payload, response_headers, raw

    def token(self):
        status, payload, headers, _ = self.call("/api/v1/auth/csrf")
        assert status == 200 and payload["headerName"] == "X-CSRF-TOKEN"
        return payload["token"]

    def post(self, path, body):
        return self.call(path, "POST", body, self.token())

    def login(self, login):
        token = self.token()
        form = urllib.parse.urlencode({
            "username": login,
            "password": os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"],
        }).encode()
        request = urllib.request.Request(
            "http://127.0.0.1:8080/api/v1/auth/login", data=form,
            method="POST", headers={
                "Content-Type": "application/x-www-form-urlencoded",
                "Accept": "application/json",
                "X-CSRF-TOKEN": token,
            })
        with self.opener.open(request, timeout=20) as response:
            assert response.status == 200
        status, payload, _, _ = self.call("/api/v1/auth/me")
        assert status == 200
        return payload


def draft():
    u = uuid4().hex
    return {
        "category": "INBOUND_FREIGHT", "sourceKind": "MANUAL_OTHER",
        "sourceKey": "V226-BATCH-" + u,
        "documentSha256": u * 2,
        "businessDate": "2026-11-24", "amount": "7.500000",
        "currency": "USD",
    }


route = f"/api/v1/finance/marketplaces/{MP_A}/cost-sources"
batch_route = route + "/batch"
owner = Browser()
identity = owner.login("v65-ci-owner")
assert identity["isOwner"] and identity["activeTenantId"] == TENANT
first20 = [draft() for _ in range(20)]
base = epoch()

# Stage twenty distinct manually claimed sources. One real SQL statement for
# V91 and ONE for 20 separate audit identities: exactly 2 epoch ticks.
status, items, headers, raw = owner.post(batch_route, first20)
assert status == 200 and len(items) == 20, (status, raw[:400])
assert "no-store" in headers.get("Cache-Control", "").lower()
ids = [item["id"] for item in items]
assert len(set(ids)) == 20
assert [v["sourceKey"] for v in items] == [v["sourceKey"] for v in first20]
assert all(v["state"] == "STAGED" and v["origin"] == "MANUAL_UNVERIFIED"
           and v["reviewedBy"] is None for v in items)
assert epoch() == base + 2, (epoch(), base)
assert audit_count(ids) == 20 and source_count(ids) == 20
assert num("SELECT count(*) FROM finance_cost_source_audit_v91 "
           "WHERE tenant_id='" + TENANT + "' AND source_id IN (" +
           ",".join("'" + x + "'" for x in ids) +
           ") AND action='STAGED'") == 20
print("V226_REAL_HTTP_BATCH_NEW " + json.dumps({
    "new_sources": len(ids), "unique_stage_audit_rows": 20,
    "committed_tenant_epoch_ticks": 2,
    "original_currency_and_status_unverified": True,
    "response_no_store": True,
}, sort_keys=True), flush=True)

# Exact replay must return original IDs, NOT generate more audit rows or epoch.
before = epoch()
status, again, _, raw = owner.post(batch_route, first20)
assert status == 200 and [v["id"] for v in again] == ids, (status, raw[:200])
assert epoch() == before and audit_count(ids) == 20
# Existing single-row create shares the exact idempotency semantics.
status, single, _, raw = owner.post(route, first20[0])
assert status == 200 and single["id"] == ids[0] and epoch() == before, (
    status, raw[:200])

# Mixed replays and three new facts: one new V91 INSERT + one audit INSERT;
# all twenty originals still have exactly one STAGED audit each.
extra = [draft() for _ in range(3)]
before = epoch()
status, mixed, _, raw = owner.post(batch_route, first20[:5] + extra)
assert status == 200 and len(mixed) == 8, (status, raw[:200])
assert [v["id"] for v in mixed[:5]] == ids[:5]
new_ids = [v["id"] for v in mixed[-3:]]
assert len(set(new_ids + ids)) == 23
assert epoch() == before + 2
assert audit_count(new_ids) == 3 and audit_count(ids) == 20
print("V226_REAL_HTTP_REPLAY " + json.dumps({
    "repeat_existing_count": 20, "repeat_tick_delta": 0,
    "mixed_existing_count": 5, "mixed_new_count": 3,
    "mixed_epoch_delta": 2,
    "replay_audit_duplicated": False,
    "single_create_replay_consistent": True
}, sort_keys=True), flush=True)

# Critical atomicity: a valid NEW row precedes a re-use of an existing key
# with changed immutable amount. Any partial write would be an accounting bug.
conflict = dict(first20[0], amount="9.000000")
candidate = draft()
before = epoch()
status, _, _, raw = owner.post(batch_route, [candidate, conflict])
assert status == 409, (status, raw[:200])
assert epoch() == before
assert num("SELECT count(*) FROM finance_cost_source_v91 "
           "WHERE tenant_id='" + TENANT + "' AND source_key='" +
           candidate["sourceKey"] + "'") == 0

# Invalid whole batch, 2 duplicate keys, 21 input facts all fail *before* DB.
bad = dict(draft(), documentSha256="0" * 64)
invalid_requests = [[draft(), bad], [draft()] * 2,
                    [draft() for _ in range(21)], []]
for request in invalid_requests:
    old = epoch()
    status, _, _, raw = owner.post(batch_route, request)
    assert status == 400, (status, raw[:180])
    assert epoch() == old
print("V226_REAL_HTTP_ROLLBACK " + json.dumps({
    "conflicting_immutable_replay_rejected": 409,
    "valid_candidate_not_partially_staged": True,
    "invalid_batch_http_400_cases": len(invalid_requests),
    "failed_request_tenant_epoch_ticks": 0,
    "max_batch_size": 20
}, sort_keys=True), flush=True)

# No tenant supplied in request. A Owner cannot write into foreign B scope.
before = epoch()
status, _, _, raw = owner.post(
    f"/api/v1/finance/marketplaces/{MP_B}/cost-sources/batch", [draft()])
assert status == 404, (status, raw[:200])
assert epoch() == before

# Anonymous must have CSRF token (so 401 is a *session* test, not 403 CSRF).
anon = Browser()
status, _, _, raw = anon.post(batch_route, [draft()])
assert status == 401, (status, raw[:200])

# Owner Session is required; A's read-only operator is not A's Owner.
staff = Browser()
staff.login("v219-disposable-scoped-staff")
status, _, _, raw = staff.post("/api/v1/auth/active-tenant",
                              {"tenantId": TENANT})
assert status == 200, (status, raw[:200])
status, _, _, raw = staff.post(batch_route, [draft()])
assert status in (403, 404), (status, raw[:200])
assert epoch() == before

# Valid owner cannot skip CSRF, regardless of batch payload.
status, _, _, raw = owner.call(batch_route, method="POST", body=[draft()])
assert status == 403, (status, raw[:200])
assert epoch() == before
print("V226_REAL_HTTP_AUTH " + json.dumps({
    "anonymous_no_session": 401, "other_tenant_marketplace": 404,
    "read_only_operator_not_allowed": True,
    "missing_csrf_rejected": 403, "no_cost_posting": True
}, sort_keys=True), flush=True)

# Real V216 Owner read remains UNKNOWN even with 23 manual STAGED sources.
read_route = (f"/api/v1/finance/marketplaces/{MP_A}/"
              f"purchase-receipts/{RECEIPT}/manual-cost-integrity")
status, preview, headers, raw = owner.call(read_route)
assert status == 200, (status, raw[:200])
assert preview["actualBatchCogs"] is None
assert preview["realizedNetProfit"] is None
assert preview["postingAllowed"] is False
assert preview["completeExternalCostCoverage"] is False
assert "no-store" in headers.get("Cache-Control", "").lower()
assert audit_count(ids) == 20 and audit_count(new_ids) == 3
print("PASS V226 real Spring Owner/CSRF/PG18 bounded set-based V91 batch: "
      "source immutable replay and mixed originals, 20 per-source audits, "
      "exact 2-tick new/mixed vs zero-tick replay, atomic invalid/conflict "
      "rollback, tenant isolation, and no actual COGS/net profit or OAuth")
