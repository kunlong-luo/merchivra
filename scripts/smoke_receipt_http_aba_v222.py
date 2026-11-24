#!/usr/bin/env python3
"""V222 deterministic *real HTTP* read-between-RR ABA, disposable PG18 ONLY.

Uses a double-gated CI-only Spring test seam, enabled for precisely one
synthetic receipt. A real logged-in Owner GET blocks AFTER RR1 COMMIT;
another PostgreSQL session commits USD->EUR->USD; RR2 sees the new V113
committed epoch and real Spring HTTP must fail 409, with no financial data.
No remote services, no actual fees, no production role/ledger changes.
"""
import http.cookiejar
import json
import os
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from urllib.error import HTTPError
from uuid import UUID

REQUIRED = {
    "MERCHIVRA_V222_DISPOSABLE_ONLY": "true",
    "MERCHIVRA_CI_V222_READ_BOUNDARY": "true",
    "SPRING_PROFILES_ACTIVE": "ci-v222",
    "GITHUB_ACTIONS": "true",
    "GITHUB_REPOSITORY": "kunlong-luo/merchivra",
    "GITHUB_REF": "refs/heads/main",
    "MERCHIVRA_SPAPI_ENABLED": "false",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED": "false",
    "DB_HOST": "127.0.0.1",
    "DB_PORT": "5432",
    "DB_NAME": "merchivra",
    "DB_USER": "merchivra",
    "DB_PASSWORD": "ci_disposable_database_password",
    "PGPASSWORD": "ci_disposable_database_password",
    "MERCHIVRA_BOOTSTRAP_USERNAME": "v65-ci-owner",
    "MERCHIVRA_BOOTSTRAP_PASSWORD": "ci-disposable-v65-only-secret-0000",
}
for key, want in REQUIRED.items():
    assert os.environ.get(key) == want, "V222 refused non-disposable setting " + key
assert os.environ.get("GITHUB_RUN_ID", "").isdigit()


def uid(n):
    return f"00000000-0000-4000-8000-{n:012x}"


PARGS = ["psql", "-X", "-qAt", "-h", "127.0.0.1", "-U", "merchivra",
         "-d", "merchivra", "-v", "ON_ERROR_STOP=1"]
receipt = uid(22201)
receipt_original = uid(21912)
order_a = uid(21910)
mp_a = uid(21904)
mp_a2 = uid(21905)


def query(sql):
    proc = subprocess.run(PARGS + ["-c", sql], capture_output=True,
                          text=True, timeout=15)
    if proc.returncode:
        raise AssertionError("V222 isolated PG18 error: " + proc.stderr[-1800:])
    return proc.stdout.strip()


tenant = str(UUID(query("""
SELECT m.tenant_id FROM membership m
JOIN local_credential c ON c.user_id=m.user_id
JOIN role_binding r ON r.tenant_id=m.tenant_id AND r.user_id=m.user_id
WHERE c.login_name='v65-ci-owner' AND r.role_code='OWNER'
 AND r.seller_account_id IS NULL AND m.status='ACTIVE';
""")))

# Do not run before V219/V220/V221 or collide with earlier synthetic records.
query(f"""
DO $v222$
BEGIN
 IF current_setting('server_version_num')::int<180000 OR
    NOT EXISTS(SELECT 1 FROM flyway_schema_history
     WHERE version='113' AND success) OR
    (SELECT count(*) FROM finance_receipt_cost_line_v112
     WHERE tenant_id='{tenant}' AND receipt_id='{receipt_original}')<>51 OR
    NOT EXISTS(SELECT 1 FROM seller_marketplace
     WHERE id='{mp_a}' AND tenant_id='{tenant}') OR
    EXISTS(SELECT 1 FROM erp_purchase_receipt WHERE id='{receipt}')
 THEN
    RAISE EXCEPTION 'V222 requires exact disposable V221 predecessor fixtures';
 END IF;
END $v222$;
""")
query(f"""
INSERT INTO erp_purchase_receipt
 (id,tenant_id,purchase_order_id,purchase_order_line_id,quantity,received_by)
SELECT '{receipt}',tenant_id,purchase_order_id,purchase_order_line_id,
       1,received_by
FROM erp_purchase_receipt
WHERE tenant_id='{tenant}' AND id='{receipt_original}';
""")
assert query("SELECT btrim(currency_code) FROM erp_purchase_order "
             f"WHERE id='{order_a}' AND tenant_id='{tenant}'") == "USD"


def epoch():
    return int(query("SELECT revision FROM finance_receipt_read_epoch_v113 "
                     f"WHERE tenant_id='{tenant}'"))


class HttpClient:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))

    def request(self, path, expected=200, method="GET", form=None, csrf=None):
        data = urllib.parse.urlencode(form).encode() if form else None
        headers = {"Accept": "application/json"}
        if form is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        if csrf is not None:
            headers["X-CSRF-TOKEN"] = csrf
        try:
            with self.opener.open(
                urllib.request.Request(
                    "http://127.0.0.1:8080"+path,
                    headers=headers, data=data, method=method),
                timeout=22
            ) as response:
                status, raw, h = response.status, response.read(), response.headers
        except HTTPError as exc:
            status, raw, h = exc.code, exc.read(), exc.headers
        assert status == expected, (path, status, expected, raw[:240])
        parsed = json.loads(raw) if (
            raw and h.get_content_type()=="application/json") else None
        return parsed, h, raw

    def login(self):
        csrf, _, _ = self.request("/api/v1/auth/csrf")
        self.request("/api/v1/auth/login", method="POST", form={
            "username": "v65-ci-owner",
            "password": os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"],
        }, csrf=csrf["token"])
        viewer, _, _ = self.request("/api/v1/auth/me")
        assert viewer["isOwner"] is True and viewer["activeTenantId"] == tenant
        assert len([c for c in self.jar if c.name=="JSESSIONID"]) == 1


client = HttpClient()
client.login()
route = ("/api/v1/finance/marketplaces/"+mp_a+
         "/purchase-receipts/"+receipt+"/manual-cost-integrity")

# Don't call the special receipt GET until the holder has the lock: the
# CI-only boundary would otherwise pass with no interleaving.
holder = subprocess.Popen(PARGS, stdin=subprocess.PIPE,
                          stdout=subprocess.DEVNULL,
                          stderr=subprocess.PIPE, text=True)


def lock_count(granted):
    g = "true" if granted else "false"
    return int(query("SELECT count(*) FROM pg_locks "
                     "WHERE locktype='advisory' AND classid=222 "
                     f"AND objid=2 AND objsubid=2 AND granted={g}"))


def await_lock(granted):
    for _ in range(130):
        if lock_count(granted):
            return
        time.sleep(0.1)
    raise AssertionError("V222 HTTP barrier not reached, or not active in Spring")


result = {}
done = threading.Event()


def http_worker():
    try:
        result["response"] = client.request(route, expected=409)
    except BaseException as exc:
        result["error"] = exc
    finally:
        done.set()


thread = None
baseline = epoch()
try:
    holder.stdin.write("SELECT pg_advisory_lock(222,2);\n")
    holder.stdin.flush()
    await_lock(True)

    # The request must reach the real service AFTER a complete first RR
    # commit and be visibly WAITING for the CI-only second-phase lock.
    thread = threading.Thread(target=http_worker, daemon=True)
    thread.start()
    await_lock(False)
    assert not done.is_set(), "HTTP request was not blocked at RR boundary"

    first = query("SELECT btrim(currency_code) FROM erp_purchase_order "
                  f"WHERE id='{order_a}' AND tenant_id='{tenant}'")
    assert first == "USD"
    query("UPDATE erp_purchase_order SET currency_code='EUR' "
          f"WHERE id='{order_a}' AND tenant_id='{tenant}';")
    query("UPDATE erp_purchase_order SET currency_code='USD' "
          f"WHERE id='{order_a}' AND tenant_id='{tenant}';")
    assert query("SELECT btrim(currency_code) FROM erp_purchase_order "
                 f"WHERE id='{order_a}' AND tenant_id='{tenant}'") == first
    assert epoch() >= baseline+2
    assert not done.is_set(), "HTTP request resumed before release"
finally:
    # Release the lock from the same real PostgreSQL session. No SIGTERM
    # while blocked in server-side pg_sleep, no pooled JDBC lock leakage.
    if holder.poll() is None:
        try:
            holder.stdin.write("SELECT pg_advisory_unlock(222,2);\n\\q\n")
            holder.stdin.flush()
            holder.communicate(timeout=8)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            holder.kill()
            holder.communicate()

assert done.wait(14), "V222 real HTTP did not finish after advisory unlock"
thread.join(timeout=2)
if "error" in result:
    raise result["error"]
assert "response" in result
error_payload, error_headers, raw = result["response"]
assert b'"plans"' not in raw and b'"actualBatchCogs"' not in raw
assert b'"realizedNetProfit"' not in raw
assert not error_payload or error_payload.get("localPlanCount") is None

# Stable NEW request after the ABA, on same real Owner session, succeeds but
# zero local proposals means UNKNOWN, not cost 0 and never approved profit.
# Other receipt does not activate the CI-specific lock.
normal, h, _ = client.request(route)
assert normal["localPlanCount"] == 0 and normal["plans"] == []
assert "NO_LOCAL_RECEIPT_COST_PROPOSAL_UNKNOWN_NOT_ZERO" in normal["blockerCodes"]
assert normal["actualBatchCogs"] is None
assert normal["realizedNetProfit"] is None
assert normal["postingAllowed"] is False
assert "no-store" in h.get("Cache-Control","").lower()
other, h, _ = client.request(
    "/api/v1/finance/marketplaces/"+mp_a2+
    "/purchase-receipts/"+receipt+"/manual-cost-integrity")
assert other["localPlanCount"] == 0 and other["actualBatchCogs"] is None
assert "no-store" in h.get("Cache-Control","").lower()

print("PASS V222 REAL Spring Owner HTTP ABA: first V216 RR committed, "
      "in-flight GET blocked at CI-only advisory xact gate, committed PG18 "
      "PO USD-EUR-USD, second RR new V113 epoch -> genuine HTTP 409 with "
      "no partial plans/COGS, stable new GET 200 UNKNOWN, other Marketplace "
      "isolation and no-store; no real external cost or profit")
