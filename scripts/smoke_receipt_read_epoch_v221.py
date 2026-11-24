#!/usr/bin/env python3
"""V221 real PG18 V113 transaction/ABA and V216 HTTP contract, disposable only.

Two independent PostgreSQL sessions prove committed A->B->A is visible via
the tenant epoch even when the business row is back to its ORIGINAL value.
A separate focused Java test proves V216 returns 409 on the inter-snapshot
epoch difference. Do not claim deterministic in-flight HTTP interleaving.
No external invoices, actual COGS, Ads, bank data or production auth.
"""
import http.cookiejar
import json
import os
import subprocess
import time
from urllib.request import Request, HTTPCookieProcessor, build_opener
from uuid import UUID

for key, expected in {
    "MERCHIVRA_V221_DISPOSABLE_ONLY": "true",
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
}.items():
    assert os.environ.get(key) == expected, "V221 requires isolated CI: " + key
assert os.environ.get("GITHUB_RUN_ID", "").isdigit()

BASE = "http://127.0.0.1:8080"
PARGS = ["psql", "-X", "-qAt", "-h", "127.0.0.1", "-U", "merchivra",
         "-d", "merchivra", "-v", "ON_ERROR_STOP=1"]


def uid(n):
    return f"00000000-0000-4000-8000-{n:012x}"


mp_a, receipt_a, receipt_extra = uid(21904), uid(21912), uid(22099)
po_a, po_b, staff_id = uid(21910), uid(21915), uid(21902)
mp_a2, mp_b = uid(21905), uid(21907)
temp_source = uid(221700)


def pg(sql):
    p = subprocess.run(PARGS + ["-c", sql], capture_output=True, text=True,
                       timeout=20)
    if p.returncode:
        raise AssertionError("V221 actual PG18 failure: " + p.stderr[-2200:])
    return p.stdout.strip()


def num(sql):
    return int(pg(sql).splitlines()[-1])


tenant_a = str(UUID(pg("""
SELECT m.tenant_id FROM membership m
JOIN local_credential c ON c.user_id=m.user_id
JOIN role_binding rb ON rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
WHERE c.login_name='v65-ci-owner' AND rb.role_code='OWNER'
 AND rb.seller_account_id IS NULL AND m.status='ACTIVE';
""")))
tenant_b = uid(21901)
assert tenant_a != tenant_b
pg(f"""
DO $v221$
BEGIN
 IF current_setting('server_version_num')::int<180000 OR
   NOT EXISTS(SELECT 1 FROM flyway_schema_history
     WHERE version='113' AND success) THEN
   RAISE EXCEPTION 'V221 requires actual PG18 and Flyway V113';
 END IF;
 IF (SELECT count(*) FROM pg_trigger
       WHERE NOT tgisinternal AND tgname LIKE '%_epoch_%_v113')<>46 THEN
   RAISE EXCEPTION 'V113 expected 45 statement + 1 tenant mutation trigger';
 END IF;
 IF (SELECT count(*) FROM erp_purchase_receipt
       WHERE tenant_id='{tenant_a}' AND id IN ('{receipt_a}','{receipt_extra}'))<>2
    OR (SELECT count(*) FROM finance_receipt_cost_line_v112
       WHERE tenant_id='{tenant_a}' AND receipt_id='{receipt_a}')<>51
    OR (SELECT count(*) FROM finance_receipt_cost_line_v112
       WHERE tenant_id='{tenant_a}' AND receipt_id='{receipt_extra}')<>1
    OR EXISTS(SELECT 1 FROM finance_cost_source_v91 WHERE id='{temp_source}') THEN
   RAISE EXCEPTION 'V221 needs exact unmodified V220 synthetic source/receipt fixture';
 END IF;
END $v221$;
""")


def epoch(tenant):
    return num("SELECT coalesce((SELECT revision FROM "
               "finance_receipt_read_epoch_v113 WHERE tenant_id='"
               + tenant + "'),0)::bigint")


def currency(order, tenant):
    return pg(f"SELECT btrim(currency_code) FROM erp_purchase_order "
              f"WHERE tenant_id='{tenant}' AND id='{order}';")


baseline = epoch(tenant_a)
assert baseline > 0 and currency(po_a, tenant_a) == "USD"

# DB-owned revision remains unchanged after an aborted SQL transaction.
pg(f"""BEGIN;
UPDATE erp_purchase_order SET currency_code='EUR'
 WHERE tenant_id='{tenant_a}' AND id='{po_a}';
ROLLBACK;""")
assert epoch(tenant_a) == baseline
assert currency(po_a, tenant_a) == "USD"

# Source activity in an independent tenant B never touches tenant A's epoch.
assert currency(po_b, tenant_b) == "EUR"
pg(f"UPDATE erp_purchase_order SET currency_code='USD' "
   f"WHERE tenant_id='{tenant_b}' AND id='{po_b}';")
pg(f"UPDATE erp_purchase_order SET currency_code='EUR' "
   f"WHERE tenant_id='{tenant_b}' AND id='{po_b}';")
assert epoch(tenant_a) == baseline
assert epoch(tenant_b) >= 2

# Complete A->B->A on a user-claimed V91 document: row absent before/after
# and old V165 source/collision data identical; revision cannot go backwards.
pg(f"""INSERT INTO finance_cost_source_v91
 (id,tenant_id,seller_marketplace_id,category,source_kind,source_key,
 source_document_sha256,business_date,currency_code,amount,created_by)
 VALUES ('{temp_source}','{tenant_a}','{mp_a}','INBOUND_FREIGHT',
 'MANUAL_OTHER','V221-TEMP-SAME-DIGEST',repeat('b',64),
 DATE '2026-11-24','USD',3.000000,'{staff_id}');""")
after_insert = epoch(tenant_a)
assert after_insert > baseline
pg(f"DELETE FROM finance_cost_source_v91 "
   f"WHERE tenant_id='{tenant_a}' AND id='{temp_source}';")
after_delete = epoch(tenant_a)
assert after_delete > after_insert
assert num("SELECT count(*) FROM finance_cost_source_v91 "
           f"WHERE id='{temp_source}'") == 0

# An intentional two-session deterministic REPEATABLE READ interleaving.
# Session H holds the (221,2) advisory gate. Reader R:
# read epoch in RR1 -> takes (221,1) -> blocks on H's (221,2).
# Coordinator then commits USD->EUR->USD, releases H, R reads again
# INSIDE RR1 (same original epoch) and in NEW RR2 (higher epoch).
# Keep a live psql session holding the advisory gate while idle.
# Killing a client blocked in server-side pg_sleep may leave its backend
# and advisory lock alive until the sleep ends; use explicit unlock instead.
holder = subprocess.Popen(
    PARGS, stdin=subprocess.PIPE,
    stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
holder.stdin.write("SELECT pg_advisory_lock(221,2);\n")
holder.stdin.flush()
reader = None


def has_lock(part):
    return num("SELECT count(*) FROM pg_locks "
               "WHERE locktype='advisory' AND granted "
               f"AND classid=221 AND objid={part} AND objsubid=2") > 0


def wait_lock(part):
    for _ in range(90):
        if has_lock(part):
            return
        time.sleep(0.1)
    raise AssertionError("V221 two-session barrier was not reached")


try:
    wait_lock(2)
    statement = f"""
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'FIRST=' || coalesce((SELECT revision
 FROM finance_receipt_read_epoch_v113
 WHERE tenant_id='{tenant_a}'),0)::bigint;
SELECT pg_advisory_lock(221,1);
SELECT pg_advisory_lock(221,2);
SELECT 'SAME_RR=' || coalesce((SELECT revision
 FROM finance_receipt_read_epoch_v113
 WHERE tenant_id='{tenant_a}'),0)::bigint;
COMMIT;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'NEXT_RR=' || coalesce((SELECT revision
 FROM finance_receipt_read_epoch_v113
 WHERE tenant_id='{tenant_a}'),0)::bigint;
COMMIT;
"""
    reader = subprocess.Popen(PARGS + ["-c", statement],
                              stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE,text=True)
    wait_lock(1)
    first_business_value = currency(po_a, tenant_a)
    assert first_business_value == "USD"
    pg(f"UPDATE erp_purchase_order SET currency_code='EUR' "
       f"WHERE tenant_id='{tenant_a}' AND id='{po_a}';")
    pg(f"UPDATE erp_purchase_order SET currency_code='USD' "
       f"WHERE tenant_id='{tenant_a}' AND id='{po_a}';")
    assert currency(po_a, tenant_a) == first_business_value
finally:
    # Release the gate at the SERVER from the SAME advisory-lock owner.
    # A SIGTERM to a sleeping psql client does not guarantee that the
    # backend will notice the closed socket before the sleep completes.
    if holder.poll() is None:
        try:
            holder.stdin.write("SELECT pg_advisory_unlock(221,2);\n\\q\n")
            holder.stdin.flush()
            holder.communicate(timeout=8)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            holder.kill()
            holder.communicate()

assert reader is not None
out, err = reader.communicate(timeout=15)
assert reader.returncode == 0, err[-1500:]
phases = dict(x.split("=", 1) for x in out.splitlines()
              if "=" in x and x.split("=", 1)[0] in
              ("FIRST", "SAME_RR", "NEXT_RR"))
assert set(phases) == {"FIRST","SAME_RR","NEXT_RR"}, (out,err)
assert int(phases["FIRST"]) == int(phases["SAME_RR"])
assert int(phases["NEXT_RR"]) >= int(phases["FIRST"]) + 2
assert epoch(tenant_a) == int(phases["NEXT_RR"])

# The normal HTTP endpoint still emits ONLY local candidate data after an
# externally unverified ABA. The dedicated Java unit test supplies a
# deterministic, independently controlled two-snapshot 409 assertion.
jar = http.cookiejar.CookieJar()
browser = build_opener(HTTPCookieProcessor(jar))


def request(path, method="GET", form=None, csrf=None):
    headers = {"Accept": "application/json"}
    data = None
    if form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        data = __import__("urllib.parse",fromlist=["urlencode"]).urlencode(form).encode()
    if csrf:
        headers["X-CSRF-TOKEN"] = csrf
    with browser.open(Request(BASE+path,method=method,headers=headers,data=data),
                      timeout=10) as response:
        assert response.status==200
        return json.loads(response.read()),response.headers


token,_=request("/api/v1/auth/csrf")
request("/api/v1/auth/login",method="POST",form={
    "username":"v65-ci-owner",
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]
},csrf=token["token"])
path=("/api/v1/finance/marketplaces/"+mp_a+"/purchase-receipts/"
      +receipt_extra+"/manual-cost-integrity")
result,headers=request(path)
assert result["localPlanCount"] == 1
assert result["plans"][0]["localReceiptCandidateAmount"] == "50.000000"
assert result["actualBatchCogs"] is None
assert result["realizedNetProfit"] is None
assert result["postingAllowed"] is False
assert "no-store" in headers.get("Cache-Control","").lower()

print("PASS V221 PG18 committed tenant epoch: 46 V113 triggers, "
      "rollback stable, foreign tenant stable, V165 source INSERT/DELETE "
      "ABA monotonic, deterministic two-connection RR interleaving "
      "SAME_RR original / NEXT_RR changed after PO USD-EUR-USD, "
      "local Owner HTTP remains unverified and profit null")
