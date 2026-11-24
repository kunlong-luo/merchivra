#!/usr/bin/env python3
"""V225 disposable PG18 V113/V115 bulk-write diagnostics; no production tune.

True V91 source DML, DB identity/append-only guards, tenant-version tick
conservation, repeatable batches, cross-tenant + same-tenant long transactions,
real Spring Owner in-flight HTTP 409. Timing only represents synthetic CI.
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

GUARDS = {
    "MERCHIVRA_V225_DISPOSABLE_ONLY": "true",
    "MERCHIVRA_V224_DISPOSABLE_ONLY": "true",
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
for key, expected in GUARDS.items():
    assert os.environ.get(key) == expected, "V225 only on disposable main: " + key
assert os.environ.get("GITHUB_RUN_ID", "").isdigit()
PARGS = ["psql", "-X", "-qAt", "-h", "127.0.0.1", "-U", "merchivra",
         "-d", "merchivra", "-v", "ON_ERROR_STOP=1"]
MP_A = "00000000-0000-4000-8000-000000005590"
MP_A2 = "00000000-0000-4000-8000-000000005591"
MP_B = "00000000-0000-4000-8000-000000005593"
TENANT_B = "00000000-0000-4000-8000-00000000558d"
STAFF = "00000000-0000-4000-8000-00000000558e"
RECEIPT = "00000000-0000-4000-8000-0000000056b9"

TABLES = (
    "finance_cost_source_v91",
    "finance_cost_source_audit_v91",
    "finance_cost_allocation_batch_v92",
    "finance_receipt_cost_plan_v112",
    "finance_receipt_cost_line_v112",
    "finance_receipt_cost_audit_v112",
    "erp_purchase_order",
    "erp_purchase_receipt",
    "seller_marketplace",
    "finance_ad_spend_reference",
    "ads_report_import",
    "finance_source_event",
    "spapi_settlement_report",
    "finance_settlement_statement",
    "finance_cost_settlement_line_link_v94",
)


def sql(cmd, timeout=24, okay_failure=False):
    process = subprocess.run(PARGS + ["-c", cmd], capture_output=True,
                             text=True, timeout=timeout)
    if process.returncode and not okay_failure:
        raise AssertionError("V225 PostgreSQL SQL failed: " + process.stderr[-1100:])
    return process if okay_failure else process.stdout.strip()


def number(cmd):
    return int(sql(cmd).splitlines()[-1])


TENANT_A = str(UUID(sql("""
SELECT m.tenant_id FROM membership m
JOIN local_credential c ON c.user_id=m.user_id
JOIN role_binding b ON b.tenant_id=m.tenant_id AND b.user_id=m.user_id
WHERE c.login_name='v65-ci-owner' AND b.role_code='OWNER'
  AND b.seller_account_id IS NULL AND m.status='ACTIVE';
""")))
assert TENANT_A != TENANT_B
sql(f"""
DO $v225$
BEGIN
 IF current_setting('server_version_num')::int < 180000
    OR NOT EXISTS (SELECT 1 FROM flyway_schema_history
                   WHERE version='115' AND success)
    OR (SELECT count(*) FROM pg_trigger
        WHERE NOT tgisinternal AND tgname LIKE '%_epoch_%_v113')<>46
    OR (SELECT count(*) FROM seller_marketplace
        WHERE (tenant_id,id) IN
          (('{TENANT_A}','{MP_A}'),('{TENANT_A}','{MP_A2}'),
           ('{TENANT_B}','{MP_B}')))<>3
    OR NOT EXISTS (SELECT 1 FROM erp_purchase_receipt
        WHERE tenant_id='{TENANT_A}' AND id='{RECEIPT}')
    OR (SELECT count(*) FROM membership
        WHERE user_id='{STAFF}' AND tenant_id IN
              ('{TENANT_A}','{TENANT_B}'))<>2
 THEN RAISE EXCEPTION 'V225 requires exact PG18 Flyway V115 V219-V224 fixtures';
 END IF;
END $v225$;
""")


def version(tenant):
    return number("SELECT coalesce((SELECT revision FROM "
                  "finance_receipt_read_epoch_v113 WHERE tenant_id='"
                  + tenant + "'),0)::bigint")


# Verify every V113 protected input has INSERT/UPDATE/DELETE transition triggers
# after V115, not just a global trigger count that could mask a missing table.
for table in TABLES:
    got = sql("SELECT string_agg(substring(tgname from "
              f"'{table}_epoch_(insert|update|delete)_v113'),',' "
              "ORDER BY tgname) FROM pg_trigger WHERE NOT tgisinternal "
              f"AND tgrelid='{table}'::regclass "
              "AND tgname LIKE '%_epoch_%_v113';")
    assert got == "delete,insert,update", (table, got)
print("V225_TRIGGER_COVERAGE " + json.dumps({
    "tables": len(TABLES),
    "statement_transition_triggers": 3 * len(TABLES),
    "tenant_status_trigger": 1,
    "v115_applied": True, "finer_marketplace_epoch_installed": False
}, sort_keys=True), flush=True)


def source_row(tenant, marketplace):
    token = uuid4()
    uid = str(token)
    digest = token.hex * 2
    key = "V225-" + token.hex
    return (uid, f"('{uid}','{tenant}','{marketplace}',"
            f"'INBOUND_FREIGHT','MANUAL_OTHER','{key}','{digest}',"
            f"DATE '2026-11-24','USD',1.000000,'{STAFF}')")


COLUMNS = ("(id,tenant_id,seller_marketplace_id,category,source_kind,"
           "source_key,source_document_sha256,business_date,currency_code,"
           "amount,created_by)")


def inserts(rows, batch):
    if batch:
        return ("INSERT INTO finance_cost_source_v91 " + COLUMNS + " VALUES " +
                ",".join(value for _, value in rows) + ";\n")
    return "".join("INSERT INTO finance_cost_source_v91 " + COLUMNS +
                   " VALUES " + value + ";\n" for _, value in rows)


def deletes(rows, batch):
    ids = [uid for uid, _ in rows]
    if batch:
        return ("DELETE FROM finance_cost_source_v91 WHERE id IN (" +
                ",".join("'" + uid + "'" for uid in ids) + ");\n")
    return "".join("DELETE FROM finance_cost_source_v91 "
                   f"WHERE id='{uid}';\n" for uid in ids)


def write_sources(tenant, marketplace, n=20, batched=True):
    rows = [source_row(tenant, marketplace) for _ in range(n)]
    before = version(tenant)
    start = time.perf_counter()
    sql("BEGIN;\n" + inserts(rows, batched) + deletes(rows, batched) +
        "COMMIT;\n", timeout=40)
    elapsed_ms = (time.perf_counter() - start) * 1000
    expected = 2 if batched else 2 * n
    assert version(tenant) == before + expected, (
        "tenant epoch conservation", batched, n, expected)
    assert number("SELECT count(*) FROM finance_cost_source_v91 "
                  "WHERE id IN (" +
                  ",".join("'" + uid + "'" for uid, _ in rows) + ")") == 0
    return elapsed_ms


# Compare identical total facts and statements in actual PG18; tests assert
# only correct epochs, not that any particular speedup must hold on shared CI.
# One SQL statement affects all 20 rows: 1 INSERT + 1 DELETE tick. Twenty
# separate SQL statements per phase: 20 + 20 ticks in the same transaction.
results = {}
for batched in (True, False):
    values = [write_sources(TENANT_A, MP_A2, 20, batched) for _ in range(4)]
    results["set_based" if batched else "row_statements"] = {
        "source_rows_per_transaction": 20,
        "transactions": len(values),
        "v113_ticks_per_transaction": 2 if batched else 40,
        "latency_median_ms": round(statistics.median(values), 2),
        "latency_p95_ms": round(sorted(values)[math.ceil(.95 * len(values))-1], 2),
        "raw_ms": [round(v, 2) for v in values],
    }
print("V225_BULK_V91 " + json.dumps({
    **results, "same_business_source_count_per_transaction": True,
    "includes_psql_startup": True, "synthetic_ci_not_production_tps": True
}, sort_keys=True), flush=True)


# V91's source fact and review state are NOT a mutable generic UPDATE target.
# An attempted no-op review UPDATE should be rejected by the existing V91
# BEFORE UPDATE guard, not treated as a safe candidate for epoch optimization.
test_row = source_row(TENANT_A, MP_A2)
before = version(TENANT_A)
sql("INSERT INTO finance_cost_source_v91 " + COLUMNS +
    " VALUES " + test_row[1] + ";")
assert version(TENANT_A) == before + 1
bad = sql("UPDATE finance_cost_source_v91 SET review_state=review_state "
          f"WHERE id='{test_row[0]}';", okay_failure=True)
assert bad.returncode != 0 and "Cost source review may be completed only once" in bad.stderr, (
    "V91 has non-idempotent database review invariants", bad.stderr[-250:])
assert version(TENANT_A) == before + 1
sql(f"DELETE FROM finance_cost_source_v91 WHERE id='{test_row[0]}';")
assert version(TENANT_A) == before + 2
print("V225_V91_GUARD " + json.dumps({
    "staged_source_noop_review_update_rejected": True,
    "failed_update_epoch_ticks": 0,
    "committed_insert_delete_ticks": 2,
    "no_auto_tuning_immutable_or_append_only_sources": True
}, sort_keys=True), flush=True)


# Long transaction: V91 writes for DIFFERENT Marketplaces in same tenant
# serialize on tenant epoch, despite distinct business source IDs. A second
# tenant can independently commit while A's transaction holds the version.
held = source_row(TENANT_A, MP_A)
waiting = source_row(TENANT_A, MP_A2)
other_tenant = source_row(TENANT_B, MP_B)
before_a, before_b = version(TENANT_A), version(TENANT_B)
holder = subprocess.Popen(PARGS, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True,
                          env={**os.environ, "PGAPPNAME": "merchivra-v225-holder"})
contender = None
lock_witness = False
cross_tenant_completed = False
try:
    holder.stdin.write("SET lock_timeout='12s';\nBEGIN;\n"
                       "INSERT INTO finance_cost_source_v91 " + COLUMNS +
                       " VALUES " + held[1] + ";\n"
                       "SELECT 'V225_HELD';\n")
    holder.stdin.flush()
    assert holder.stdout.readline().strip() == "V225_HELD"
    contender = subprocess.Popen(
        PARGS + ["-c", "SET lock_timeout='12s'; BEGIN; "
                 "INSERT INTO finance_cost_source_v91 " + COLUMNS +
                 " VALUES " + waiting[1] + "; COMMIT;"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        env={**os.environ, "PGAPPNAME": "merchivra-v225-contender"})
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if number("SELECT count(*) FROM pg_stat_activity "
                  "WHERE application_name='merchivra-v225-contender' "
                  "AND wait_event_type='Lock'") > 0:
            lock_witness = True
            break
        if contender.poll() is not None:
            break
        time.sleep(.06)
    assert lock_witness, "real same-tenant other-Marketplace V91 Lock not observed"
    assert contender.poll() is None
    # The other tenant does not acquire the locked A epoch row.
    sql("INSERT INTO finance_cost_source_v91 " + COLUMNS + " VALUES " +
        other_tenant[1] + ";", timeout=5)
    cross_tenant_completed = True
    assert version(TENANT_B) == before_b + 1
    assert version(TENANT_A) == before_a  # holder has NOT COMMITTED
finally:
    if holder.poll() is None:
        try:
            holder.stdin.write("COMMIT;\n\\q\n")
            holder.stdin.flush()
            holder.communicate(timeout=9)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            holder.kill()
            holder.communicate()
    if contender is not None:
        _, err = contender.communicate(timeout=15)
        assert contender.returncode == 0, err[-900:]
assert version(TENANT_A) == before_a + 2
assert version(TENANT_B) == before_b + 1
sql("DELETE FROM finance_cost_source_v91 WHERE id IN ('" +
    held[0] + "','" + waiting[0] + "');")
sql(f"DELETE FROM finance_cost_source_v91 WHERE id='{other_tenant[0]}';")
assert version(TENANT_A) == before_a + 3
assert version(TENANT_B) == before_b + 2
print("V225_LONG_TRANSACTION " + json.dumps({
    "same_tenant_foreign_marketplace_lock_wait": lock_witness,
    "different_tenant_can_commit_before_holder": cross_tenant_completed,
    "before_holder_commit_A_epoch_unchanged": True,
    "after_holder_and_waiter_commit_A_ticks": 2,
    "other_tenant_tick_independent": True,
    "cleanup_both_tenant_revisions_conserved": True
}, sort_keys=True), flush=True)


class Owner:
    def __init__(self):
        jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(jar))
        csrf = self.call("/api/v1/auth/csrf")[1]["token"]
        self.call("/api/v1/auth/login", method="POST", data={
            "username": "v65-ci-owner",
            "password": os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]}, csrf=csrf)
        identity = self.call("/api/v1/auth/me")[1]
        assert identity["isOwner"] and identity["activeTenantId"] == TENANT_A

    def call(self, path, method="GET", data=None, csrf=None):
        body = urllib.parse.urlencode(data).encode() if data else None
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        if csrf:
            headers["X-CSRF-TOKEN"] = csrf
        request = urllib.request.Request("http://127.0.0.1:8080" + path,
                                         data=body, method=method,
                                         headers=headers)
        try:
            with self.opener.open(request, timeout=23) as resp:
                status, raw, h = resp.status, resp.read(), resp.headers
        except HTTPError as exc:
            status, raw, h = exc.code, exc.read(), exc.headers
        payload = json.loads(raw) if (raw and
            h.get_content_type() == "application/json") else None
        return status, payload, h, raw


owner = Owner()
route = ("/api/v1/finance/marketplaces/" + MP_A +
         "/purchase-receipts/" + RECEIPT + "/manual-cost-integrity")


def safe_status(result):
    status, payload, headers, raw = result
    assert status in (200, 409), (status, raw[:200])
    if status == 409:
        assert b'"plans"' not in raw
        assert b'"actualBatchCogs"' not in raw and b'"realizedNetProfit"' not in raw
    else:
        assert payload["realizedNetProfit"] is None
        assert payload["actualBatchCogs"] is None
        assert payload["postingAllowed"] is False
        assert "no-store" in headers.get("Cache-Control", "").lower()
    return status


def count_gate(granted):
    return number("SELECT count(*) FROM pg_locks WHERE locktype='advisory' "
                  "AND classid=222 AND objid=2 AND objsubid=2 AND granted=" +
                  ("true" if granted else "false"))


def await_gate(granted):
    for _ in range(140):
        if count_gate(granted):
            return
        time.sleep(.1)
    raise AssertionError("V225 Owner real HTTP first RR CI barrier missing")


def interleaved_owner(source_case):
    """Use actual V91 INSERT+DELETE, not PO/Marketplace scope mutations."""
    gate = subprocess.Popen(PARGS, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                            text=True)
    answer = {}
    event = threading.Event()
    a0, b0 = version(TENANT_A), version(TENANT_B)

    def request():
        try:
            answer["result"] = owner.call(route)
        except BaseException as error:
            answer["error"] = error
        finally:
            event.set()

    t = None
    try:
        gate.stdin.write("SELECT pg_advisory_lock(222,2);\n")
        gate.stdin.flush()
        await_gate(True)
        t = threading.Thread(target=request, daemon=True)
        t.start()
        await_gate(False)
        assert not event.is_set(), "V225 HTTP was not paused between two RRs"
        if source_case != "no_write":
            tenant = TENANT_B if source_case == "other_tenant" else TENANT_A
            marketplace = MP_B if source_case == "other_tenant" else MP_A2
            record = source_row(tenant, marketplace)
            # TWO separately committed real V91 statements restore same
            # original source population while advancing revision twice.
            sql("INSERT INTO finance_cost_source_v91 " + COLUMNS +
                " VALUES " + record[1] + ";")
            sql(f"DELETE FROM finance_cost_source_v91 WHERE id='{record[0]}';")
        assert version(TENANT_A) - a0 == (
            2 if source_case == "same_tenant_other_marketplace" else 0)
        assert version(TENANT_B) - b0 == (2 if source_case == "other_tenant" else 0)
        assert not event.is_set()
    finally:
        if gate.poll() is None:
            try:
                gate.stdin.write("SELECT pg_advisory_unlock(222,2);\n\\q\n")
                gate.stdin.flush()
                gate.communicate(timeout=8)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                gate.kill()
                gate.communicate()
    assert t is not None and event.wait(16)
    t.join(timeout=2)
    if "error" in answer:
        raise answer["error"]
    return safe_status(answer["result"])


interleaving = {
    "no_write": [interleaved_owner("no_write") for _ in range(2)],
    "other_tenant": [interleaved_owner("other_tenant") for _ in range(2)],
    "same_tenant_other_marketplace": [
        interleaved_owner("same_tenant_other_marketplace") for _ in range(3)],
}
assert interleaving == {
    "no_write": [200, 200],
    "other_tenant": [200, 200],
    "same_tenant_other_marketplace": [409, 409, 409],
}
assert safe_status(owner.call(route)) == 200
print("V225_FORCED_V91_HTTP " + json.dumps({
    "results": interleaving,
    "real_source_insert_then_delete_committed_twice": True,
    "same_rows_visible_before_and_after": True,
    "forced_interleaving_not_natural_production_409": True
}, sort_keys=True), flush=True)


# Non-forced owner GET during a small, finite batched V91 workload.
# Report the raw sample without asserting a positive 409 minimum or SLO.
def writer_bursts():
    for _ in range(16):
        write_sources(TENANT_A, MP_A2, n=10, batched=True)


latencies = []
statuses = []
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    workers = [pool.submit(writer_bursts) for _ in range(2)]
    for _ in range(80):
        start = time.perf_counter()
        statuses.append(safe_status(owner.call(route)))
        latencies.append((time.perf_counter() - start) * 1000)
    for job in workers:
        job.result(timeout=120)
values = sorted(latencies)
assert len(statuses) == 80
p95 = round(values[math.ceil(.95 * len(values)) - 1], 2)
p99 = round(values[math.ceil(.99 * len(values)) - 1], 2)
print("V225_UNFORCED_MIXED_HTTP " + json.dumps({
    "get_requests": 80,
    "observed_409": statuses.count(409),
    "observed_200": statuses.count(200),
    "p95_ms": p95,
    "p99_ms": p99,
    "writers": 2,
    "committed_source_transactions": 32,
    "source_rows_touched": 640,
    "committed_tenant_epoch_ticks": 64,
    "synthetic_ci_not_representative_production_distribution": True
}, sort_keys=True), flush=True)
assert safe_status(owner.call(route)) == 200
print("PASS V225 PG18 45 transition triggers, V91 immutable guard, bulk "
      "vs row-statement epoch conservation, tenant long-transaction lock, "
      "true V91 source ABA Owner HTTP409 and mixed read/write diagnostic; "
      "no external COGS/profit attested, no producer OAuth enabled")
