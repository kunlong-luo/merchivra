#!/usr/bin/env python3
"""V224: actual PG18 Flyway V114 no-op epoch + ABA / real Owner HTTP proof.

Disposable main A3 only. Uses V219-V223 synthetic fixtures and CI-only V222
after-first-RR gate. All business changes remain local/unverified; this does
NOT establish natural production HTTP409 rate, landed COGS or net profit.
"""
import concurrent.futures
import http.cookiejar
import json
import math
import os
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from urllib.error import HTTPError
from uuid import UUID, uuid4

REQUIRED = {
    "MERCHIVRA_V224_DISPOSABLE_ONLY": "true",
    "MERCHIVRA_V222_DISPOSABLE_ONLY": "true",
    "MERCHIVRA_CI_V222_READ_BOUNDARY": "true",
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
for k, v in REQUIRED.items():
    assert os.environ.get(k) == v, "V224 requires isolated CI: " + k
assert os.environ.get("GITHUB_RUN_ID", "").isdigit()

PARGS = ["psql", "-X", "-qAt", "-h", "127.0.0.1", "-U", "merchivra",
         "-d", "merchivra", "-v", "ON_ERROR_STOP=1"]
PO = "00000000-0000-4000-8000-000000005596"      # V219 21910
MP_A = "00000000-0000-4000-8000-000000005590"    # V219 21904
MP_A2 = "00000000-0000-4000-8000-000000005591"   # V219 21905
RECEIPT = "00000000-0000-4000-8000-0000000056b9"  # V222 22201
STAFF = "00000000-0000-4000-8000-00000000558e"  # V219 21902


def pg(sql, timeout=20):
    p = subprocess.run(PARGS + ["-c", sql], capture_output=True, text=True,
                       timeout=timeout)
    if p.returncode:
        raise AssertionError("V224 PG18 SQL failed: " + p.stderr[-1200:])
    return p.stdout.strip()


def num(sql):
    return int(pg(sql).splitlines()[-1])


TENANT = str(UUID(pg("""
SELECT m.tenant_id FROM membership m
JOIN local_credential c ON c.user_id=m.user_id
JOIN role_binding b ON b.tenant_id=m.tenant_id AND b.user_id=m.user_id
WHERE c.login_name='v65-ci-owner' AND b.role_code='OWNER'
  AND b.seller_account_id IS NULL AND m.status='ACTIVE';
""")))


def epoch():
    return num("SELECT coalesce((SELECT revision FROM "
               "finance_receipt_read_epoch_v113 WHERE tenant_id='"
               + TENANT + "'),0)::bigint")


def currency():
    return pg("SELECT btrim(currency_code) FROM erp_purchase_order "
              f"WHERE tenant_id='{TENANT}' AND id='{PO}';")


def timezone(mp):
    return pg("SELECT btrim(timezone) FROM seller_marketplace "
              f"WHERE tenant_id='{TENANT}' AND id='{mp}';")


pg(f"""
DO $v224$
BEGIN
 IF current_setting('server_version_num')::int < 180000
   OR NOT EXISTS (SELECT 1 FROM flyway_schema_history
                 WHERE version='114' AND success)
   OR (SELECT count(*) FROM pg_trigger
       WHERE NOT tgisinternal AND tgname LIKE '%_epoch_%_v113') <> 46
   OR (SELECT count(*) FROM erp_purchase_order
       WHERE tenant_id='{TENANT}' AND id='{PO}' AND currency_code='USD') <> 1
   OR (SELECT count(*) FROM seller_marketplace
       WHERE tenant_id='{TENANT}' AND id IN ('{MP_A}','{MP_A2}')) <> 2
   OR NOT EXISTS (SELECT 1 FROM erp_purchase_receipt
       WHERE tenant_id='{TENANT}' AND id='{RECEIPT}')
 THEN RAISE EXCEPTION 'V224 requires Flyway V114 and exact isolated fixtures';
 END IF;
END $v224$;
""")
assert timezone(MP_A) == timezone(MP_A2) == "Etc/UTC"
base = epoch()

# V114: old/new transition row MULTISETS are identical for these two tables.
pg(f"UPDATE erp_purchase_order SET currency_code=currency_code "
   f"WHERE tenant_id='{TENANT}' AND id='{PO}';")
pg(f"UPDATE seller_marketplace SET timezone=timezone "
   f"WHERE tenant_id='{TENANT}' AND id='{MP_A2}';")
pg(f"UPDATE seller_marketplace SET timezone=timezone "
   f"WHERE tenant_id='{TENANT}' AND id IN ('{MP_A}','{MP_A2}');")
pg(f"UPDATE erp_purchase_order SET currency_code='EUR' "
   f"WHERE tenant_id='{TENANT}' AND id='00000000-0000-4000-8000-ffffffffffff';")
assert epoch() == base, "V114 identical / zero-row UPDATE must not tick"
assert currency() == "USD" and timezone(MP_A2) == "Etc/UTC"

# True changes A->B and B->A in DIFFERENT committed transactions must each
# advance exactly once even though visible data returns to the old value.
pg(f"UPDATE erp_purchase_order SET currency_code='EUR' "
   f"WHERE tenant_id='{TENANT}' AND id='{PO}';")
assert epoch() == base + 1 and currency() == "EUR"
pg(f"UPDATE erp_purchase_order SET currency_code='USD' "
   f"WHERE tenant_id='{TENANT}' AND id='{PO}';")
assert epoch() == base + 2 and currency() == "USD"
pg(f"BEGIN; UPDATE erp_purchase_order SET currency_code='EUR' "
   f"WHERE tenant_id='{TENANT}' AND id='{PO}'; ROLLBACK;")
assert epoch() == base + 2 and currency() == "USD"

# Shared tenant still serializes genuine cross-Marketplace changes. Hold one
# REAL purchase order transaction open and prove a different Marketplace row
# waits on a PG Lock rather than on a shared business row.
holder = subprocess.Popen(PARGS, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True,
                          env={**os.environ, "PGAPPNAME": "merchivra-v224-holder"})
writer = None
wait_seen = False
try:
    holder.stdin.write(
        "SET lock_timeout='12s';\nBEGIN;\n"
        f"UPDATE erp_purchase_order SET currency_code='EUR' "
        f"WHERE tenant_id='{TENANT}' AND id='{PO}';\n"
        "SELECT 'V224_HELD';\n")
    holder.stdin.flush()
    assert holder.stdout.readline().strip() == "V224_HELD", "holder not started"
    writer_sql = (
        "SET lock_timeout='12s'; BEGIN; "
        f"UPDATE seller_marketplace SET timezone='Etc/GMT' "
        f"WHERE tenant_id='{TENANT}' AND id='{MP_A2}'; COMMIT;")
    writer = subprocess.Popen(PARGS + ["-c", writer_sql],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              text=True,
                              env={**os.environ,
                                   "PGAPPNAME": "merchivra-v224-contender"})
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if num("SELECT count(*) FROM pg_stat_activity "
               "WHERE application_name='merchivra-v224-contender' "
               "AND wait_event_type='Lock'") > 0:
            wait_seen = True
            break
        if writer.poll() is not None:
            break
        time.sleep(.05)
    assert wait_seen, "V224 real second-Marketplace writer did not wait for lock"
    assert writer.poll() is None
finally:
    if holder.poll() is None:
        try:
            holder.stdin.write("COMMIT;\n\\q\n")
            holder.stdin.flush()
            holder.communicate(timeout=10)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            holder.kill()
            holder.communicate()
    if writer is not None:
        out, err = writer.communicate(timeout=15)
        assert writer.returncode == 0, err[-1000:]
assert currency() == "EUR" and timezone(MP_A2) == "Etc/GMT"
assert epoch() == base + 4, "two genuine DML txs each must tick once"
pg(f"UPDATE erp_purchase_order SET currency_code='USD' "
   f"WHERE tenant_id='{TENANT}' AND id='{PO}';")
pg(f"UPDATE seller_marketplace SET timezone='Etc/UTC' "
   f"WHERE tenant_id='{TENANT}' AND id='{MP_A2}';")
assert epoch() == base + 6 and currency() == "USD"
assert timezone(MP_A2) == "Etc/UTC"
print("V224_PG18_NOOP " + json.dumps({
    "identical_update_ticks": 0, "zero_row_update_ticks": 0,
    "committed_aba_ticks": 2, "rolled_back_ticks": 0,
    "different_business_rows_same_tenant_lock_wait": wait_seen,
    "original_epoch": base, "final_epoch": epoch()}, sort_keys=True), flush=True)


class Owner:
    def __init__(self):
        jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(jar))
        token = self.call("/api/v1/auth/csrf")[1]["token"]
        self.call("/api/v1/auth/login", method="POST", data={
            "username": "v65-ci-owner",
            "password": os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},
            csrf=token)
        info = self.call("/api/v1/auth/me")[1]
        assert info["isOwner"] and info["activeTenantId"] == TENANT

    def call(self, route, method="GET", data=None, csrf=None):
        body = urllib.parse.urlencode(data).encode() if data else None
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        if csrf:
            headers["X-CSRF-TOKEN"] = csrf
        try:
            with self.opener.open(urllib.request.Request(
                "http://127.0.0.1:8080" + route, data=body, method=method,
                headers=headers), timeout=22) as response:
                status, raw, h = response.status, response.read(), response.headers
        except HTTPError as exc:
            status, raw, h = exc.code, exc.read(), exc.headers
        value = json.loads(raw) if (raw and
            h.get_content_type() == "application/json") else None
        return status, value, h, raw


owner = Owner()
route = ("/api/v1/finance/marketplaces/" + MP_A +
         "/purchase-receipts/" + RECEIPT + "/manual-cost-integrity")


def check_response(result):
    status, payload, headers, raw = result
    assert status in (200, 409), (status, raw[:180])
    if status == 409:
        assert b'"plans"' not in raw
        assert b'"actualBatchCogs"' not in raw and b'"realizedNetProfit"' not in raw
    else:
        assert payload["realizedNetProfit"] is None
        assert payload["actualBatchCogs"] is None
        assert payload["postingAllowed"] is False
        assert "no-store" in headers.get("Cache-Control", "").lower()
    return status


# Real HTTP owner in-flight between two RR: identical cross-Marketplace UPDATE
# does not cause false 409. Real cross-Marketplace A->B->A still causes 409.
def forced_http(with_change):
    gate = subprocess.Popen(PARGS, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                            text=True)
    output = {}
    done = threading.Event()
    version = epoch()

    def request():
        try:
            output["http"] = owner.call(route)
        except BaseException as exc:
            output["error"] = exc
        finally:
            done.set()

    def lock_count(granted):
        return num("SELECT count(*) FROM pg_locks "
                   "WHERE locktype='advisory' AND classid=222 AND objid=2 "
                   "AND objsubid=2 AND granted=" +
                   ("true" if granted else "false"))

    def wait_lock(granted):
        for _ in range(130):
            if lock_count(granted):
                return
            time.sleep(.1)
        raise AssertionError("V224 real Owner HTTP read boundary unavailable")

    thread = None
    try:
        gate.stdin.write("SELECT pg_advisory_lock(222,2);\n")
        gate.stdin.flush()
        wait_lock(True)
        thread = threading.Thread(target=request, daemon=True)
        thread.start()
        wait_lock(False)
        assert not done.is_set(), "GET not paused between RR1 and RR2"
        if with_change:
            pg(f"UPDATE seller_marketplace SET timezone='Etc/GMT' "
               f"WHERE tenant_id='{TENANT}' AND id='{MP_A2}';")
            pg(f"UPDATE seller_marketplace SET timezone='Etc/UTC' "
               f"WHERE tenant_id='{TENANT}' AND id='{MP_A2}';")
        else:
            pg(f"UPDATE seller_marketplace SET timezone=timezone "
               f"WHERE tenant_id='{TENANT}' AND id='{MP_A2}';")
        assert epoch() - version == (2 if with_change else 0)
        assert not done.is_set(), "GET resumed prematurely"
    finally:
        if gate.poll() is None:
            try:
                gate.stdin.write("SELECT pg_advisory_unlock(222,2);\n\\q\n")
                gate.stdin.flush()
                gate.communicate(timeout=8)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                gate.kill()
                gate.communicate()
    assert thread is not None and done.wait(14)
    thread.join(timeout=2)
    if "error" in output:
        raise output["error"]
    return check_response(output["http"])


no_change = [forced_http(False) for _ in range(3)]
true_aba = [forced_http(True) for _ in range(3)]
assert no_change == [200] * 3
assert true_aba == [409] * 3
print("V224_FORCED_OWNER_HTTP " + json.dumps({
    "identical_other_marketplace_update": {"200": 3, "409": 0},
    "committed_other_marketplace_ABA": {"200": 0, "409": 3},
    "forced_interleaving_not_natural_409_rate": True}, sort_keys=True),
    flush=True)

# Diagnostic mixture rather than a forced boundary. There is NO production
# load distribution; a 0 observed count is NOT evidence of 0% production 409.
def source_change():
    token = uuid4()
    uid = str(token)
    key = "V224-" + token.hex
    digest = token.hex * 2
    pg("BEGIN;"
       "INSERT INTO finance_cost_source_v91 "
       "(id,tenant_id,seller_marketplace_id,category,source_kind,"
       "source_key,source_document_sha256,business_date,currency_code,"
       "amount,created_by) VALUES "
       f"('{uid}','{TENANT}','{MP_A2}',"
       f"'INBOUND_FREIGHT','MANUAL_OTHER','{key}','{digest}',"
       f"DATE '2026-11-24','USD',1.000000,'{STAFF}');"
       f"DELETE FROM finance_cost_source_v91 WHERE id='{uid}' "
       f"AND tenant_id='{TENANT}';COMMIT;")


def measure_http(n):
    statuses = []
    durations = []
    for _ in range(n):
        start = time.perf_counter()
        statuses.append(check_response(owner.call(route)))
        durations.append((time.perf_counter() - start) * 1000)
    p95 = sorted(durations)[max(0, math.ceil(.95 * len(durations)) - 1)]
    return {"requests": len(statuses), "http_409": statuses.count(409),
            "observed_409_fraction": round(statuses.count(409) / len(statuses), 4),
            "p95_ms": round(p95, 2)}


baseline_http = measure_http(20)
assert baseline_http["http_409"] == 0
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    workers = [pool.submit(lambda: [source_change() for _ in range(45)])
               for _ in range(2)]
    mixed_http = measure_http(100)
    for f in workers:
        f.result(timeout=70)
assert epoch() >= base + 6 + 6 + 180
print("V224_MIXED_OWNER_HTTP " + json.dumps(
    {"baseline": baseline_http, "two_v91_writers": mixed_http,
     "source_writes_committed": 90,
     "synthetic_ci_not_production_409_rate": True}, sort_keys=True),
    flush=True)
assert currency() == "USD" and timezone(MP_A2) == "Etc/UTC"
print("PASS V224 PG18 V114 identical UPDATE suppression, real-ABA committed "
      "epoch, two-business-row Lock wait, genuine Owner HTTP 200/409 and "
      "mixed load diagnostic; no real COGS/profit or production approval")
