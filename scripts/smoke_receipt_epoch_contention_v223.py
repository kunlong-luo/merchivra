#!/usr/bin/env python3
"""V223: disposable PG18 V113 contention + cross-Marketplace HTTP409 evidence.

Uses the ACTUAL V91 INSERT/DELETE statement triggers in committed transactions
and the ACTUAL V216 Owner HTTP route. No V113 replacement, synthetic accounting
approval, or production traffic. CI timings are diagnostic, not a production SLA.
Runs after the V219-V222 fixtures in the isolated full-suite PG18 job.
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
    "MERCHIVRA_V223_DISPOSABLE_ONLY": "true",
    "MERCHIVRA_V222_DISPOSABLE_ONLY": "true",
    "SPRING_PROFILES_ACTIVE": "ci-v222",
    "MERCHIVRA_CI_V222_READ_BOUNDARY": "true",
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
for key, expected in REQUIRED.items():
    assert os.environ.get(key) == expected, "V223 is disposable-only: " + key
assert os.environ.get("GITHUB_RUN_ID", "").isdigit(), "V223 requires an Actions run"

PARGS = ["psql", "-X", "-qAt", "-h", "127.0.0.1", "-U", "merchivra",
         "-d", "merchivra", "-v", "ON_ERROR_STOP=1"]
STAFF = "00000000-0000-4000-8000-00000000558e"  # V219 21902
MARKET_A = "00000000-0000-4000-8000-000000005590"  # 21904
MARKET_A2 = "00000000-0000-4000-8000-000000005591"  # 21905
MARKET_B = "00000000-0000-4000-8000-000000005593"  # 21907
TENANT_B = "00000000-0000-4000-8000-00000000558d"  # 21901
RECEIPT = "00000000-0000-4000-8000-0000000056b9"  # V222 22201


def pg(sql, timeout=20):
    proc = subprocess.run(PARGS + ["-c", sql], capture_output=True,
                          text=True, timeout=timeout)
    if proc.returncode:
        raise AssertionError("V223 PG18 statement failure: " + proc.stderr[-900:])
    return proc.stdout.strip()


tenant_a = str(UUID(pg("""
SELECT m.tenant_id FROM membership m
JOIN local_credential c ON c.user_id=m.user_id
JOIN role_binding b ON b.user_id=m.user_id AND b.tenant_id=m.tenant_id
WHERE c.login_name='v65-ci-owner' AND b.role_code='OWNER'
  AND b.seller_account_id IS NULL AND m.status='ACTIVE';
""")))
assert tenant_a != TENANT_B
pg(f"""
DO $v223$
BEGIN
 IF current_setting('server_version_num')::int < 180000
    OR NOT EXISTS(SELECT 1 FROM flyway_schema_history
       WHERE version='113' AND success)
    OR (SELECT count(*) FROM pg_trigger
        WHERE NOT tgisinternal AND tgname LIKE '%_epoch_%_v113')<>46
    OR (SELECT count(*) FROM seller_marketplace
        WHERE (tenant_id,id) IN (
          ('{tenant_a}','{MARKET_A}'),
          ('{tenant_a}','{MARKET_A2}'),
          ('{TENANT_B}','{MARKET_B}')))<>3
    OR (SELECT count(*) FROM membership
        WHERE user_id='{STAFF}' AND tenant_id IN
          ('{tenant_a}','{TENANT_B}'))<>2
    OR NOT EXISTS(SELECT 1 FROM erp_purchase_receipt
        WHERE id='{RECEIPT}' AND tenant_id='{tenant_a}')
 THEN RAISE EXCEPTION 'V223 requires exact V219-V222 disposable PG18 fixtures';
 END IF;
END $v223$;
""")


def epoch(tenant):
    return int(pg("SELECT coalesce((SELECT revision FROM "
                  "finance_receipt_read_epoch_v113 WHERE tenant_id='"
                  + tenant + "'),0)::bigint"))


def change(tenant, marketplace):
    """Two installed V91 statement triggers; facts absent after COMMIT.

    This is an ephemeral, unreviewed source. It can only advance the local
    version, never make actual landed cost / profit verified.
    """
    token = uuid4()
    ident = str(token)
    key = "V223-" + token.hex
    digest = token.hex * 2
    return (f"INSERT INTO finance_cost_source_v91 "
            f"(id,tenant_id,seller_marketplace_id,category,source_kind,"
            f"source_key,source_document_sha256,business_date,currency_code,"
            f"amount,created_by) VALUES "
            f"('{ident}','{tenant}','{marketplace}',"
            f"'INBOUND_FREIGHT','MANUAL_OTHER','{key}','{digest}',"
            f"DATE '2026-11-24','USD',1.000000,'{STAFF}');\n"
            f"DELETE FROM finance_cost_source_v91 WHERE id='{ident}' "
            f"AND tenant_id='{tenant}';\n")


def percentile(values, fraction):
    data = sorted(values)
    return round(data[max(0, math.ceil(fraction * len(data)) - 1)], 2)


def worker(tenant, marketplace, repeats, barrier):
    """One persistent psql/backend per independent real transaction writer."""
    proc = subprocess.Popen(PARGS, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, bufsize=1)
    samples = []
    try:
        proc.stdin.write("SET lock_timeout='12s';\n"
                         "SET statement_timeout='15s';\n"
                         "SELECT 'V223_READY';\n")
        proc.stdin.flush()
        assert proc.stdout.readline().strip() == "V223_READY", "psql startup"
        barrier.wait(timeout=20)
        for i in range(repeats):
            marker = "V223_DONE_" + str(i)
            start = time.perf_counter()
            proc.stdin.write("BEGIN;\n" + change(tenant, marketplace)
                             + "COMMIT;\nSELECT '" + marker + "';\n")
            proc.stdin.flush()
            response = proc.stdout.readline().strip()
            end = time.perf_counter()
            if response != marker:
                raise AssertionError("V223 writer stopped before commit: " +
                                     response + " " + proc.stderr.read()[-600:])
            samples.append((start, end))
        proc.stdin.write("\\q\n")
        proc.stdin.flush()
        proc.communicate(timeout=10)
        assert proc.returncode == 0, "V223 psql process failed"
        return samples
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.communicate()


def run_case(name, writers, transactions_per_writer):
    baseline = {t: epoch(t) for t, _ in writers}
    barrier = threading.Barrier(len(writers))
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(writers)) as pool:
        futures = [pool.submit(worker, t, m, transactions_per_writer, barrier)
                   for t, m in writers]
        samples = [item for future in futures for item in future.result(timeout=100)]
    expected = {t: sum(2 * transactions_per_writer
                       for wt, _ in writers if wt == t) for t in baseline}
    for tenant, count in expected.items():
        assert epoch(tenant) - baseline[tenant] == count, (
            name, "revision conservation failed", tenant, count)
    starts = [s for s, _ in samples]
    ends = [e for _, e in samples]
    elapsed = max(ends) - min(starts)
    latencies = [(e - s) * 1000 for s, e in samples]
    return {"scenario": name, "workers": len(writers),
            "transactions": len(samples),
            "committed_v113_ticks": sum(expected.values()),
            "transactions_per_second": round(len(samples) / elapsed, 2),
            "p50_ms": percentile(latencies, .50),
            "p95_ms": percentile(latencies, .95),
            "p99_ms": percentile(latencies, .99),
            "max_ms": round(max(latencies), 2)}


# Same actual V91 immutable-source DML path and per-worker transaction count.
# No shared business row is updated: the tenant epoch is the common row lock.
# Cross-tenant fixture has two tenants only; it is NOT a production scale test.
cases = [
    ("one_writer_one_marketplace", [(tenant_a, MARKET_A)], 40),
    ("eight_writers_one_marketplace", [(tenant_a, MARKET_A)] * 8, 30),
    ("eight_writers_two_marketplaces_one_tenant",
     [(tenant_a, MARKET_A), (tenant_a, MARKET_A2)] * 4, 30),
    ("eight_writers_two_tenants",
     [(tenant_a, MARKET_A), (TENANT_B, MARKET_B)] * 4, 30),
]
measurements = [run_case(*case) for case in cases]
for measurement in measurements:
    print("V223_PG18_CONTENTION " + json.dumps(measurement, sort_keys=True),
          flush=True)


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
        who = self.call("/api/v1/auth/me")[1]
        assert who["isOwner"] and who["activeTenantId"] == tenant_a

    def call(self, route, method="GET", data=None, csrf=None):
        body = urllib.parse.urlencode(data).encode() if data else None
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        if csrf is not None:
            headers["X-CSRF-TOKEN"] = csrf
        request = urllib.request.Request(
            "http://127.0.0.1:8080" + route, data=body, method=method,
            headers=headers)
        try:
            with self.opener.open(request, timeout=22) as result:
                status, raw, h = result.status, result.read(), result.headers
        except HTTPError as exc:
            status, raw, h = exc.code, exc.read(), exc.headers
        payload = json.loads(raw) if (raw and
            h.get_content_type() == "application/json") else None
        return status, payload, h, raw


owner = Owner()
route = ("/api/v1/finance/marketplaces/" + MARKET_A +
         "/purchase-receipts/" + RECEIPT + "/manual-cost-integrity")
status, result, headers, _ = owner.call(route)
assert status == 200 and result["realizedNetProfit"] is None
assert result["actualBatchCogs"] is None and not result["postingAllowed"]


def lock_count(granted):
    return int(pg("SELECT count(*) FROM pg_locks WHERE locktype='advisory' "
                  "AND classid=222 AND objid=2 AND objsubid=2 AND granted="
                  + ("true" if granted else "false")))


def await_lock(granted):
    for _ in range(130):
        if lock_count(granted):
            return
        time.sleep(.1)
    raise AssertionError("V223 never reached real V222 after-RR1 barrier")


def forced_http_interleaving(target):
    """Forced, not naturally observed, 409 rate from a scoped foreign write."""
    holder = subprocess.Popen(PARGS, stdin=subprocess.PIPE,
                              stdout=subprocess.DEVNULL,
                              stderr=subprocess.PIPE, text=True)
    reply = {}
    done = threading.Event()
    before_a, before_b = epoch(tenant_a), epoch(TENANT_B)

    def request():
        try:
            reply["data"] = owner.call(route)
        except BaseException as exc:
            reply["error"] = exc
        finally:
            done.set()

    thread = None
    try:
        holder.stdin.write("SELECT pg_advisory_lock(222,2);\n")
        holder.stdin.flush()
        await_lock(True)
        thread = threading.Thread(target=request, daemon=True)
        thread.start()
        await_lock(False)
        assert not done.is_set(), "V223 HTTP was not between RR snapshots"
        if target == "same_marketplace":
            pg("BEGIN;\n" + change(tenant_a, MARKET_A) + "COMMIT;")
        if target == "foreign_marketplace_same_tenant":
            pg("BEGIN;\n" + change(tenant_a, MARKET_A2) + "COMMIT;")
        if target == "foreign_tenant":
            pg("BEGIN;\n" + change(TENANT_B, MARKET_B) + "COMMIT;")
        expect_a = 2 if target in (
            "same_marketplace", "foreign_marketplace_same_tenant") else 0
        expect_b = 2 if target == "foreign_tenant" else 0
        assert epoch(tenant_a) - before_a == expect_a
        assert epoch(TENANT_B) - before_b == expect_b
        assert not done.is_set(), "V223 first/second RR barrier escaped"
    finally:
        if holder.poll() is None:
            try:
                holder.stdin.write("SELECT pg_advisory_unlock(222,2);\n\\q\n")
                holder.stdin.flush()
                holder.communicate(timeout=8)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                holder.kill()
                holder.communicate()
    assert thread is not None and done.wait(14), "V223 HTTP did not finish"
    thread.join(timeout=2)
    if "error" in reply:
        raise reply["error"]
    status, body, headers, raw = reply["data"]
    expected = 409 if target in (
        "same_marketplace", "foreign_marketplace_same_tenant") else 200
    assert status == expected, (target, status, expected, raw[:200])
    if status == 409:
        assert b'"plans"' not in raw and b'"actualBatchCogs"' not in raw
        assert b'"realizedNetProfit"' not in raw
    else:
        assert body["realizedNetProfit"] is None
        assert body["actualBatchCogs"] is None
        assert body["postingAllowed"] is False
        assert "no-store" in headers.get("Cache-Control", "").lower()
    return status


# These are causal conditional fractions with a purposely held read boundary.
# They MUST NOT be reported as the naturally occurring production 409 rate.
counts = {}
for scenario in ("no_write", "foreign_tenant",
                 "foreign_marketplace_same_tenant", "same_marketplace"):
    outcomes = [forced_http_interleaving(scenario) for _ in range(4)]
    counts[scenario] = {"forced_samples": len(outcomes),
                        "http_409": outcomes.count(409),
                        "http_409_fraction": outcomes.count(409) / len(outcomes)}
    print("V223_FORCED_HTTP409 " +
          json.dumps({"scenario": scenario, **counts[scenario]},
                     sort_keys=True), flush=True)
assert counts["no_write"]["http_409"] == 0
assert counts["foreign_tenant"]["http_409"] == 0
assert counts["foreign_marketplace_same_tenant"]["http_409"] == 4
assert counts["same_marketplace"]["http_409"] == 4
print("PASS V223 PG18 V113 real-trigger contention and forced cross-"
      "Marketplace Owner HTTP409; synthetic only; 409 fractions conditional, "
      "not a production traffic estimate; actual COGS/profit remain NULL")
