#!/usr/bin/env python3
"""V227 real Spring/PG18 batch-size, replay and cross-Marketplace pressure.

Disposable main CI only. Measures request durations/PG WAL bytes in small
synthetic samples; never claims production SLO, external verification or COGS.
"""
import concurrent.futures
import http.cookiejar
import json
import math
import os
import statistics
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from urllib.error import HTTPError
from uuid import UUID, uuid4

REQUIRED = {
    "MERCHIVRA_V227_DISPOSABLE_ONLY": "true",
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
for key, value in REQUIRED.items():
    assert os.environ.get(key) == value, "V227 disposable main only: " + key
assert os.environ.get("GITHUB_RUN_ID", "").isdigit()
PARGS = ["psql", "-X", "-qAt", "-h", "127.0.0.1", "-U", "merchivra",
         "-d", "merchivra", "-v", "ON_ERROR_STOP=1"]


def pg(sql, timeout=20):
    proc = subprocess.run(PARGS + ["-c", sql], capture_output=True,
                          text=True, timeout=timeout)
    assert proc.returncode == 0, ("V227 PG18 check failed", proc.stderr[-900:])
    return proc.stdout.strip()


def number(sql):
    return int(pg(sql).splitlines()[-1])


TENANT = str(UUID(pg("""
SELECT m.tenant_id FROM membership m
JOIN local_credential c ON c.user_id=m.user_id
JOIN role_binding b ON b.tenant_id=m.tenant_id AND b.user_id=m.user_id
WHERE c.login_name='v65-ci-owner' AND b.role_code='OWNER'
  AND b.seller_account_id IS NULL AND m.status='ACTIVE';
""")))
MP_A = "00000000-0000-4000-8000-000000005590"
MP_A2 = "00000000-0000-4000-8000-000000005591"
RECEIPT = "00000000-0000-4000-8000-0000000056b9"
pg(f"""
DO $v227$
BEGIN
 IF current_setting('server_version_num')::int < 180000
    OR NOT EXISTS (SELECT 1 FROM flyway_schema_history
                   WHERE version='115' AND success)
    OR (SELECT count(*) FROM pg_trigger
        WHERE NOT tgisinternal AND tgname LIKE '%_epoch_%_v113') <> 46
    OR (SELECT count(*) FROM seller_marketplace
        WHERE tenant_id='{TENANT}' AND id IN ('{MP_A}','{MP_A2}')) <> 2
    OR NOT EXISTS (SELECT 1 FROM erp_purchase_receipt
        WHERE tenant_id='{TENANT}' AND id='{RECEIPT}')
 THEN RAISE EXCEPTION 'V227 requires V219-V226 PG18 fixtures';
 END IF;
END $v227$;
""")


def epoch():
    return number("SELECT coalesce((SELECT revision FROM "
                  f"finance_receipt_read_epoch_v113 WHERE tenant_id='{TENANT}'),0)")


def wal_lsn():
    return pg("SELECT pg_current_wal_lsn()")


def wal_bytes(before, after):
    return number(f"SELECT pg_wal_lsn_diff('{after}','{before}')::bigint")


def count_sources(keys):
    return number("SELECT count(*) FROM finance_cost_source_v91 "
                  f"WHERE tenant_id='{TENANT}' AND source_key IN (" +
                  ",".join("'" + key + "'" for key in keys) + ")")


def count_audit(keys):
    return number("SELECT count(*) FROM finance_cost_source_audit_v91 a "
                  "JOIN finance_cost_source_v91 s ON s.id=a.source_id "
                  "AND s.tenant_id=a.tenant_id "
                  f"WHERE a.tenant_id='{TENANT}' AND s.source_key IN (" +
                  ",".join("'" + key + "'" for key in keys) + ") AND "
                  "a.action='STAGED'")


class Browser:
    def __init__(self):
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        csrf = self.call("/api/v1/auth/csrf")[1]["token"]
        body = urllib.parse.urlencode({
            "username": "v65-ci-owner",
            "password": os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"],
        }).encode()
        req = urllib.request.Request("http://127.0.0.1:8080/api/v1/auth/login",
                                     method="POST", data=body, headers={
                "Content-Type": "application/x-www-form-urlencoded",
                "X-CSRF-TOKEN": csrf, "Accept": "application/json"})
        with self.opener.open(req, timeout=22) as resp:
            assert resp.status == 200
        me = self.call("/api/v1/auth/me")
        assert me[0] == 200 and me[1]["isOwner"]
        assert me[1]["activeTenantId"] == TENANT

    def call(self, route, method="GET", body=None):
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            headers["X-CSRF-TOKEN"] = self.call("/api/v1/auth/csrf")[1]["token"]
            data = json.dumps(body).encode()
        request = urllib.request.Request("http://127.0.0.1:8080" + route,
                                         method=method, data=data,
                                         headers=headers)
        try:
            with self.opener.open(request, timeout=30) as r:
                status, raw, h = r.status, r.read(), r.headers
        except HTTPError as e:
            status, raw, h = e.code, e.read(), e.headers
        payload = (json.loads(raw) if raw and
                   h.get_content_type() == "application/json" else None)
        return status, payload, h, raw

    def post(self, marketplace, drafts):
        route = (f"/api/v1/finance/marketplaces/{marketplace}/"
                 "cost-sources/batch")
        start = time.perf_counter()
        result = self.call(route, "POST", drafts)
        ms = (time.perf_counter() - start) * 1000
        return result, ms


owner = Browser()
owner2 = Browser()
read_route = (f"/api/v1/finance/marketplaces/{MP_A}/"
              f"purchase-receipts/{RECEIPT}/manual-cost-integrity")
assert owner.call(read_route)[0] == 200


def draft():
    uid = uuid4().hex
    return {"category": "INBOUND_FREIGHT",
            "sourceKind": "MANUAL_OTHER",
            "sourceKey": "V227-BATCH-" + uid,
            "documentSha256": uid * 2,
            "businessDate": "2026-11-24", "amount": "3.250000",
            "currency": "USD"}


def assert_success(result, drafts):
    status, data, headers, raw = result
    assert status == 200 and isinstance(data, list), (status, raw[:180])
    assert len(data) == len(drafts)
    assert "no-store" in headers.get("Cache-Control", "").lower()
    assert [x["sourceKey"] for x in data] == [x["sourceKey"] for x in drafts]
    assert all(x["origin"] == "MANUAL_UNVERIFIED" and
               x["state"] == "STAGED" and x["reviewedBy"] is None for x in data)
    return [x["id"] for x in data]


def pct(values, portion):
    values = sorted(values)
    return round(values[math.ceil(len(values)*portion)-1], 2)


# Separate phases so exact tenant epoch +2/+0 is not confused with another
# concurrent writer's valid commits. Preserve each source audit identity.
size_stats = {}
for size in (1, 5, 20):
    ms_new, ms_replay, wal_new, wal_replay = [], [], [], []
    for _ in range(4):
        drafts = [draft() for _ in range(size)]
        before, lsn = epoch(), wal_lsn()
        response, duration = owner.post(MP_A, drafts)
        ids = assert_success(response, drafts)
        after_lsn = wal_lsn()
        assert epoch() == before + 2
        assert len(set(ids)) == size
        keys = [d["sourceKey"] for d in drafts]
        assert count_sources(keys) == size and count_audit(keys) == size
        ms_new.append(duration)
        wal_new.append(wal_bytes(lsn, after_lsn))
        before, lsn = epoch(), wal_lsn()
        result, duration = owner.post(MP_A, drafts)
        assert assert_success(result, drafts) == ids
        after_lsn = wal_lsn()
        assert epoch() == before
        assert count_sources(keys) == size and count_audit(keys) == size
        ms_replay.append(duration)
        wal_replay.append(wal_bytes(lsn, after_lsn))
    size_stats[str(size)] = {
        "new_ms_median": round(statistics.median(ms_new), 2),
        "new_ms_p95": pct(ms_new, .95),
        "replay_ms_median": round(statistics.median(ms_replay), 2),
        "replay_ms_p95": pct(ms_replay, .95),
        "new_wal_bytes_median": statistics.median(wal_new),
        "replay_wal_bytes_median": statistics.median(wal_replay),
        "new_epoch_ticks": 2,
        "replay_epoch_ticks": 0,
        "samples_per_case": 4,
    }
print("V227_SIZE_REPLAY " + json.dumps({
    "cases": size_stats, "real_spring_pg18": True,
    "wal_from_global_lsn_includes_unrelated_activity": True,
    "not_production_latency_or_wal_budget": True
}, sort_keys=True), flush=True)


# 0%, 50%, 100% replay within the bounded 20-row API. Old IDs/audits and
# exact new identities must persist through mixed input ordering.
ratios = {}
for old_count in (0, 10, 20):
    originals = [draft() for _ in range(20)]
    if old_count:
        result, _ = owner.post(MP_A, originals[:old_count])
        old_ids = assert_success(result, originals[:old_count])
    else:
        old_ids = []
    mixed = originals[:old_count] + [draft() for _ in range(20-old_count)]
    before = epoch()
    result, _ = owner.post(MP_A, mixed)
    identities = assert_success(result, mixed)
    assert identities[:old_count] == old_ids
    assert len(set(identities)) == 20
    assert epoch() - before == (0 if old_count == 20 else 2)
    keys = [d["sourceKey"] for d in mixed]
    assert count_sources(keys) == 20 and count_audit(keys) == 20
    ratios[str(old_count*5)] = {
        "existing_sources": old_count, "new_sources": 20-old_count,
        "epoch_ticks": 0 if old_count == 20 else 2,
        "duplicate_audits": 0,
    }
print("V227_REPLAY_RATIO " + json.dumps(ratios, sort_keys=True), flush=True)


# Two concurrently authenticated Owner sessions write independently scoped
# marketplaces within the SAME tenant. Their serializable V113 epoch must
# account for all successful new batches, not exact per-thread deltas.
start = epoch()
begin = threading.Barrier(2)
def worker(browser, marketplace):
    durations = []
    batches = []
    begin.wait(timeout=8)
    for i in range(8):
        docs = [draft() for _ in range(5)]
        result, ms = browser.post(marketplace, docs)
        batches.append((docs, assert_success(result, docs)))
        durations.append(ms)
    return batches, durations

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
    a = executor.submit(worker, owner, MP_A)
    b = executor.submit(worker, owner2, MP_A2)
    batch_a, duration_a = a.result(timeout=120)
    batch_b, duration_b = b.result(timeout=120)
assert epoch() - start == 32, "16 real new batches = 32 V113 ticks globally"
all_batches = batch_a + batch_b
all_ids = [id for _, ids in all_batches for id in ids]
assert len(set(all_ids)) == 80
all_keys = [d["sourceKey"] for docs, _ in all_batches for d in docs]
assert count_sources(all_keys) == 80 and count_audit(all_keys) == 80
read_status, preview, headers, raw = owner.call(read_route)
assert read_status == 200, (read_status, raw[:160])
assert preview["actualBatchCogs"] is None
assert preview["realizedNetProfit"] is None
assert preview["postingAllowed"] is False
assert preview["completeExternalCostCoverage"] is False
assert "no-store" in headers.get("Cache-Control", "").lower()
print("V227_CONCURRENT_MARKETPLACES " + json.dumps({
    "same_tenant_marketplaces": 2, "owner_sessions": 2,
    "new_batches_per_marketplace": 8, "source_rows": 80,
    "independent_stage_audits": 80, "exact_global_epoch_ticks": 32,
    "http_batch_p95_ms": pct(duration_a + duration_b, .95),
    "http_batch_p99_ms": pct(duration_a + duration_b, .99),
    "never_post_realized_cogs_or_profit": True,
    "synthetic_ci_not_production_slo": True,
}, sort_keys=True), flush=True)


# Long held Marketplace FOR UPDATE lock: real Owner POST must WAIT rather
# than bypass existing replay serialization. Once released, it is exact +2.
holder = subprocess.Popen(PARGS, stdin=subprocess.PIPE,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True,
                          env={**os.environ,
                               "PGAPPNAME": "merchivra-v227-lock-holder"})
wait_start = epoch()
blocked = [draft()]
waiting = {}
thread = None
seen = False
try:
    holder.stdin.write("SET lock_timeout='14s';\nBEGIN;\n"
                       f"SELECT id FROM seller_marketplace WHERE tenant_id='{TENANT}' "
                       f"AND id='{MP_A}' FOR UPDATE;\n"
                       "SELECT 'V227_HELD';\n")
    holder.stdin.flush()
    # The first query prints the ID, then marker.
    assert holder.stdout.readline().strip() == MP_A
    assert holder.stdout.readline().strip() == "V227_HELD"

    def attempted():
        try:
            waiting["result"] = owner2.post(MP_A, blocked)
        except BaseException as exc:
            waiting["error"] = exc

    thread = threading.Thread(target=attempted, daemon=True)
    thread.start()
    end = time.monotonic() + 9
    while time.monotonic() < end:
        # Observe a PostgreSQL session waiting on a *real* DB lock, not
        # a guessed request delay or arbitrary sleep.
        if number("SELECT count(*) FROM pg_stat_activity "
                  "WHERE datname='merchivra' AND wait_event_type='Lock' "
                  "AND query LIKE '%seller_marketplace%'") > 0:
            seen = True
            break
        if not thread.is_alive():
            break
        time.sleep(.05)
    assert seen, "V227 real HTTP request did not wait on Marketplace row lock"
    assert thread.is_alive() and epoch() == wait_start
finally:
    if holder.poll() is None:
        try:
            holder.stdin.write("COMMIT;\n\\q\n")
            holder.stdin.flush()
            holder.communicate(timeout=9)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            holder.kill()
            holder.communicate()
assert thread is not None
thread.join(timeout=20)
assert not thread.is_alive() and "error" not in waiting, waiting
assert_success(waiting["result"][0], blocked)
assert epoch() == wait_start + 2
assert count_audit([blocked[0]["sourceKey"]]) == 1
print("V227_LOCK_WAIT " + json.dumps({
    "real_owner_http_waited_for_marketplace_for_update": seen,
    "no_uncommitted_epoch_read": True,
    "after_release_epoch_ticks": 2,
    "audit_identity_preserved": True,
}, sort_keys=True), flush=True)

print("PASS V227 PG18 real Owner batch 1/5/20 new/replay, "
      "0/50/100% replay, two Marketplace 16-batch +32 tick conservation, "
      "real HTTP row lock and no actual cost/profit; synthetic metrics only")
