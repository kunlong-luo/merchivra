#!/usr/bin/env python3
"""V248: two real PG18 connections, installed V118 function, disposable schema.
No public financial row writes, external proof, physical inventory or COGS.
"""
import os
import subprocess
import time
from uuid import uuid4

for key, expected in {
    "MERCHIVRA_V248_DISPOSABLE_ONLY": "true",
    "GITHUB_ACTIONS": "true",
    "GITHUB_REPOSITORY": "kunlong-luo/merchivra",
    "GITHUB_REF": "refs/heads/main",
    "MERCHIVRA_SPAPI_ENABLED": "false",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED": "false",
    "DB_HOST": "127.0.0.1", "DB_PORT": "5432",
    "DB_NAME": "merchivra", "DB_USER": "merchivra",
    "DB_PASSWORD": "ci_disposable_database_password",
    "PGPASSWORD": "ci_disposable_database_password",
}.items():
    if os.environ.get(key) != expected:
        raise SystemExit("V248 requires disposable main CI: " + key)
if not os.environ.get("GITHUB_RUN_ID", "").isdigit():
    raise SystemExit("V248 requires GitHub run ID")

P = ["psql", "-X", "-qAt", "-h", "127.0.0.1", "-p", "5432",
     "-U", "merchivra", "-d", "merchivra", "-v", "ON_ERROR_STOP=1"]
schema = "v248_" + uuid4().hex
tenant, receipt, actor, first, second, third, fourth = (str(uuid4()) for _ in range(7))
scope = f"SET search_path TO {schema},public; "
signal = int(uuid4().hex[:12], 16)

def pg(sql, expected=None):
    p = subprocess.run(P + ["-c", sql], capture_output=True, text=True, timeout=20)
    if expected is not None:
        if p.returncode == 0 or expected not in p.stderr:
            raise AssertionError(p.stderr[-1500:] or "Expected rejection")
    elif p.returncode:
        raise AssertionError(p.stderr[-1500:])
    return p.stdout.strip()

def insert(claim, lo, hi):
    return ("INSERT INTO finance_sale_lot_cost_range_v117 "
            "(tenant_id,claim_id,unit_from,unit_to,recorded_by) "
            f"VALUES ('{tenant}','{claim}',{lo},{hi},'{actor}');")

def await_true(query):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if pg(query) == "t":
            return
        time.sleep(0.05)
    raise AssertionError("V248 synchronization condition not reached: " + query)

def race(holder_claim, holder_lo, holder_hi, rival_claim, rival_lo, rival_hi, commit):
    holder = subprocess.Popen(P, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                              stderr=subprocess.PIPE, text=True)
    rival = None
    label = schema + ("_commit" if commit else "_rollback")
    try:
        holder.stdin.write(scope + "BEGIN ISOLATION LEVEL READ COMMITTED; "
                           + insert(holder_claim, holder_lo, holder_hi)
                           + f" SELECT pg_advisory_xact_lock({signal});\n")
        holder.stdin.flush()
        # The advisory signal can only be acquired after the insert succeeds.
        await_true(f"SELECT NOT pg_try_advisory_lock({signal})")
        rival = subprocess.Popen(P + ["-c", scope + f"SET application_name='{label}'; "
                                       "SET lock_timeout='18s'; "
                                       + insert(rival_claim, rival_lo, rival_hi)],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        await_true("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                   f"WHERE application_name='{label}' AND cardinality(pg_blocking_pids(pid))>0)")
        holder.stdin.write(("COMMIT" if commit else "ROLLBACK") + ";\n")
        holder.stdin.flush()
        holder.stdin.close()
        holder.wait(timeout=20)
        if holder.returncode != 0:
            raise AssertionError(holder.stderr.read()[-1500:])
        _, error = rival.communicate(timeout=20)
        if commit:
            assert rival.returncode != 0 and "overlapping range or inconsistent receipt basis" in error, error
        else:
            assert rival.returncode == 0, error
    finally:
        for proc in (rival, holder):
            if proc is not None and proc.poll() is None:
                proc.kill()
                proc.wait(timeout=5)

assert 180000 <= int(pg("SHOW server_version_num")) < 190000
assert pg("SELECT count(*) FROM public.flyway_schema_history WHERE version IN ('117','118') AND success") == "2"
pg(f"CREATE SCHEMA {schema}")
try:
    # Shadow relations shared by both connections. No public business data or
    # fake foreign-key success: this checks the installed trigger, not public FKs.
    pg(scope + f"""
CREATE TABLE erp_purchase_receipt(id uuid,tenant_id uuid,PRIMARY KEY(tenant_id,id));
CREATE TABLE finance_sale_lot_claim_v106(
 id uuid,tenant_id uuid,receipt_id uuid,assigned_quantity integer,
 snapshot_accepted_quantity integer,PRIMARY KEY(tenant_id,id));
CREATE TABLE finance_sale_lot_retraction_v106(tenant_id uuid,claim_id uuid);
CREATE TABLE finance_sale_lot_cost_range_v117
 (LIKE public.finance_sale_lot_cost_range_v117 INCLUDING ALL);
CREATE TRIGGER v248_capture BEFORE INSERT ON finance_sale_lot_cost_range_v117
 FOR EACH ROW EXECUTE FUNCTION public.finance_sale_lot_cost_range_capture_v117();
INSERT INTO erp_purchase_receipt VALUES ('{receipt}','{tenant}');
INSERT INTO finance_sale_lot_claim_v106 VALUES
 ('{first}','{tenant}','{receipt}',2,4),
 ('{second}','{tenant}','{receipt}',2,4),
 ('{third}','{tenant}','{receipt}',2,4),
 ('{fourth}','{tenant}','{receipt}',2,4);
""")
    # The rival starts before the holder commits and must observe its row
    # after waiting for the receipt lock, rather than its original snapshot.
    race(first, 0, 2, second, 0, 2, True)
    assert pg(scope + "SELECT count(*) FROM finance_sale_lot_cost_range_v117") == "1"
    # Rollback must not permanently consume coordinates.
    race(third, 2, 4, fourth, 2, 4, False)
    assert pg(scope + "SELECT count(*)||':'||sum(unit_to-unit_from) FROM finance_sale_lot_cost_range_v117") == "2:4"
    assert pg(scope + f"SELECT count(*) FROM finance_sale_lot_cost_range_v117 WHERE claim_id IN ('{second}','{third}')") == "0"
    pg(scope + "BEGIN ISOLATION LEVEL REPEATABLE READ; " + insert(second, 0, 2),
       "range binding requires READ COMMITTED")
finally:
    pg(f"DROP SCHEMA {schema} CASCADE")
print("PASS V248 actual PG18 V118 range race: lock wait, committed overlap rejected, rollback permits rival, RR rejected")
