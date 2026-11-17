#!/usr/bin/env python3
"""V175-A disposable PostgreSQL 18 / actual HTTP QC schema and denial check.

This is a migration, FK/trigger and negative HTTP smoke; it does NOT emulate a
real warehouse or prove the three-owner positive flow. Never use production DB.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import uuid4

assert os.environ.get("GITHUB_ACTIONS") == "true"
assert os.environ.get("MERCHIVRA_V175_DISPOSABLE_ONLY") == "true"
assert os.environ.get("DB_HOST") == "127.0.0.1"
assert os.environ.get("DB_NAME") == "merchivra"
assert os.environ.get("MERCHIVRA_DEMO_ENABLED") == "true"
assert os.environ.get("MERCHIVRA_SPAPI_ENABLED") == "false"
assert os.environ.get("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
assert os.environ.get("MERCHIVRA_SPAPI_ALLOW_WRITES", "false") == "false"
BASE = "http://127.0.0.1:8080"

def sql(query: str) -> str:
    p = subprocess.run(
        ["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
         "-h", "127.0.0.1", "-U", "merchivra", "-d", "merchivra", "-Atc", query],
        env={**os.environ, "PGPASSWORD": os.environ["DB_PASSWORD"]},
        capture_output=True, text=True, timeout=20)
    assert p.returncode == 0, "V175 synthetic PG18 metadata query failed"
    return p.stdout.strip()

def call(client, path, method="GET", payload=None, expected=200, form=False,
         csrf=None):
    headers = {"Accept": "application/json"}
    body = None
    if payload is not None:
        body = (urlencode(payload) if form else json.dumps(payload)).encode()
        headers["Content-Type"] = ("application/x-www-form-urlencoded"
                                   if form else "application/json")
    if csrf and method == "POST":
        headers["X-CSRF-TOKEN"] = csrf
    try:
        with client.open(Request(BASE + path, headers=headers, method=method,
                                 data=body), timeout=20) as response:
            status = response.status
            data = response.read()
            cache = response.headers.get("Cache-Control", "")
    except HTTPError as err:
        status = err.code
        data = err.read()
        cache = err.headers.get("Cache-Control", "")
    assert status == expected, (method, path, status, expected)
    if status == 200 and path.endswith("/wms-qc-review"):
        assert "no-store" in cache.lower()
    try:
        return json.loads(data)
    except (ValueError, TypeError):
        return None

assert sql("SHOW server_version_num").startswith("18")
assert sql("SELECT count(*) FROM flyway_schema_history WHERE version='100' AND success") == "1"
assert sql("""SELECT count(*) FROM pg_trigger
    WHERE tgname IN ('finance_wms_qc_case_actor_v100',
      'finance_wms_qc_decision_actor_v100',
      'finance_wms_qc_case_immutable_v100',
      'finance_wms_qc_decision_immutable_v100')
    AND NOT tgisinternal AND tgenabled='O'""") == "4"
assert sql("""SELECT count(*) FROM pg_constraint
    WHERE conname IN ('finance_wms_observation_qc_scope_v100',
       'finance_wms_qc_case_v100_tenant_id_observation_id_key',
       'finance_wms_qc_decision_v100_tenant_id_case_id_key')""") == "3"
assert sql("""SELECT count(*) FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    WHERE t.relname='finance_wms_qc_case_v100' AND c.contype='f'
      AND pg_get_constraintdef(c.oid) LIKE
      '%observation_id, seller_marketplace_id, event_id, intake_id, warehouse_id%'""") == "1"
for table in ("finance_wms_qc_case_v100", "finance_wms_qc_decision_v100"):
    assert sql(f"SELECT count(*) FROM {table}") == "0"
    assert sql(f"""SELECT count(*) FROM pg_trigger
       WHERE tgrelid='{table}'::regclass AND NOT tgisinternal
         AND tgname LIKE '%immutable_v100'""") == "1"
for col in ("operator_identity_verified", "physical_receipt_verified",
            "inventory_posted", "cogs_reversed"):
    assert sql(f"""SELECT count(*) FROM pg_constraint
      WHERE conrelid='finance_wms_qc_case_v100'::regclass
        AND pg_get_constraintdef(oid) LIKE '%{col} = false%'""") == "1", col
guard = sql("""SELECT pg_get_functiondef('finance_wms_qc_actor_guard_v100'::regproc)""")
assert "signed_importer" in guard and "qc_requester" in guard
assert "23514" in guard and "qc_requester" in guard and "signed_importer" in guard
# Behavioral actor independence is tested by actual PG18 trigger invocations
# in smoke_wms_qc_actor_pg18_v175.sql; do not assert PL/pgSQL whitespace.

before = [sql("SELECT count(*) FROM " + t) for t in
          ("erp_local_stock_movement", "finance_profit_report_job")]
client = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
prefix = f"/api/v1/finance/marketplaces/{uuid4()}/events/{uuid4()}/return-intakes/{uuid4()}/wms-qc-review"
call(client, prefix, expected=401)
csrf = call(client, "/api/v1/auth/csrf")["token"]
call(client, "/api/v1/auth/login", "POST",
     {"username": os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
      "password": os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},
     form=True, csrf=csrf)
csrf = call(client, "/api/v1/auth/csrf")["token"]
me = call(client, "/api/v1/auth/me")
assert me["isOwner"] and me["activeTenantId"]
mp = sql("SELECT id FROM seller_marketplace WHERE tenant_id='%s' LIMIT 1" %
         me["activeTenantId"])
root = f"/api/v1/finance/marketplaces/{mp}/events/{uuid4()}/return-intakes/{uuid4()}/wms-qc-review"
call(client, root, expected=404)
call(client, root, "POST",
     {"qcQuantity": 0, "qcCondition": "UNKNOWN", "evidenceReference": "QC-FAKE-0001",
      "explanation": "Zero quantity must fail closed"},
     csrf=csrf, expected=400)
call(client, root, "POST",
     {"qcQuantity": 1, "qcCondition": "UNKNOWN", "evidenceReference": "QC-FAKE-0001",
      "explanation": "Unknown intake must not disclose signed source"},
     csrf=csrf, expected=404)
call(client, root + "/" + str(uuid4()) + "/acknowledge", "POST",
     {"reason": "Unknown QC case cannot be acknowledged"},
     csrf=csrf, expected=404)
call(client, root + "/" + str(uuid4()) + "/reject", "POST",
     {"reason": "Unknown QC case cannot be rejected"},
     csrf=csrf, expected=404)
after = [sql("SELECT count(*) FROM " + t) for t in
         ("erp_local_stock_movement", "finance_profit_report_job")]
assert before == after, "V175 negative QC flow must not post stock/profit"
print("PASS V175 PG18 V100 applied; scoped unique FK, 4 active immutable/actor "
      "triggers, false finance flags, Owner/anonymous/invalid HTTP denial; "
      "three-owner positive QC and real WMS still NOT certified")
