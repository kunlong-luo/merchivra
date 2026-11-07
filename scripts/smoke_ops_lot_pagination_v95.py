#!/usr/bin/env python3
"""V95 authenticated operations/lot approvals pagination; disposable PG18 Mock only.

Runs after V92/V93/V94 local fixture scripts; no remote Amazon calls or stock posting.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, HTTPCookieProcessor, build_opener
from uuid import uuid4

assert os.getenv("DB_HOST") == "127.0.0.1" and os.getenv("DB_NAME") == "merchivra"
assert os.getenv("MERCHIVRA_DEMO_ENABLED") == "true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED") == "false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES", "false") == "false"

http = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = ""

def sql(query):
    env = {**os.environ, "PGPASSWORD": os.environ["DB_PASSWORD"]}
    p = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
        "-h", "127.0.0.1", "-U", "merchivra", "-d", "merchivra", "-Atc", query],
        env=env, text=True, capture_output=True, check=False)
    assert p.returncode == 0, (query[:320], p.stderr[-1600:])
    return p.stdout.strip()

def call(path, method="GET", body=None, expected=200, form=False):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = (urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
    if method != "GET" and csrf:
        headers["X-CSRF-TOKEN"] = csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path, method=method,
            headers=headers, data=data), timeout=50) as response:
            status, raw = response.status, response.read()
            cache = response.headers.get("Cache-Control", "")
    except HTTPError as exc:
        status, raw, cache = exc.code, exc.read(), ""
    assert status == expected, (method, path, status, expected, raw[:750])
    if status==200 and "/page?" in path:
        assert "no-store" in cache, (path, cache)
    try:
        return json.loads(raw)
    except (ValueError, TypeError):
        return raw

def get(path, **query):
    return call(path+"?"+urlencode(query))

def traverse(path, **filters):
    rows, cursor, pages = [], None, 0
    while True:
        params = {"limit":50, **filters}
        if cursor:
            params["cursor"] = cursor
        data = get(path, **params)
        assert data["pageSize"] == 50 and len(data["items"])<=50
        pages += 1
        assert pages < 45
        rows.extend(data["items"])
        if not data["hasMore"]:
            assert data["nextCursor"] is None
            return rows, pages
        assert len(data["items"])==50 and data["nextCursor"]
        cursor = data["nextCursor"]

call("/api/v1/operations/findings/page?limit=50",expected=401)
call("/api/v1/purchasing/lot-exceptions/warehouses/"+
     str(uuid4())+"/page?limit=50",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me")
assert me["isOwner"]
tenant,actor=me["activeTenantId"],me["userId"]
market=sql(f"""SELECT id FROM seller_marketplace
 WHERE tenant_id='{tenant}' ORDER BY id LIMIT 1""")
warehouse=sql(f"""SELECT id FROM erp_local_warehouse
 WHERE tenant_id='{tenant}' AND warehouse_code='V92PAGEWAREHOUSE'""")
batch=sql(f"""SELECT receipt_id,product_sku_id FROM erp_receipt_quality_batch
 WHERE tenant_id='{tenant}' AND warehouse_id='{warehouse}'
 AND status='PENDING_QC' LIMIT 1""").split("|")
bin_id=sql(f"""SELECT id FROM erp_local_stock_bin
 WHERE tenant_id='{tenant}' AND warehouse_id='{warehouse}'
 AND bin_code='QC-HOLD'""")
assert market and warehouse and len(batch)==2 and bin_id

# V95 no automatic stock, purchase or Amazon writes; these are local PG18
# fixtures including nonposting COUNT_OBSERVATION evidence only.
stock_before=sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")
po_before=sql(f"""SELECT count(*) FROM erp_purchase_order WHERE tenant_id='{tenant}'""")
queue="/api/v1/operations/findings"
added=sql(f"""WITH inserted AS (
 INSERT INTO ops_finding(
 id,tenant_id,seller_marketplace_id,rule_code,source_key,title,
 severity,state,data_origin,last_scan_id,changed_at,snoozed_until)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{market}'::uuid,
 'INVENTORY_UNKNOWN','V95-OPS-'||lpad(i::text,3,'0'),
 'V95 local Mock operations finding','WARNING',
 CASE WHEN i<=145 THEN 'OPEN' WHEN i<=155 THEN 'ACKNOWLEDGED'
 ELSE 'DONE' END,
 'MOCK',gen_random_uuid(),now()-i*interval '1 minute',
 CASE WHEN i<=10 THEN now()+interval '1 day' ELSE NULL END
 FROM generate_series(1,165) i RETURNING id
) SELECT count(*) FROM inserted""")
assert added=="165",added
assert len(call(queue+"?filter=ACTIVE"))==100
all_rows,all_pages=traverse(queue+"/page",filter="ALL")
active_rows,active_pages=traverse(queue+"/page",filter="ACTIVE")
actionable_rows,_=traverse(queue+"/page",filter="ACTIONABLE")
our_all=[r for r in all_rows if r["sourceKey"].startswith("V95-OPS-")]
our_active=[r for r in active_rows if r["sourceKey"].startswith("V95-OPS-")]
our_action=[r for r in actionable_rows if r["sourceKey"].startswith("V95-OPS-")]
assert (len(our_all),len(our_active),len(our_action))==(165,155,145)
assert len({r["id"] for r in all_rows})==len(all_rows)
assert all(r["state"] in ("OPEN","ACKNOWLEDGED") for r in active_rows)
assert all(not (r["snoozedUntil"] and r["snoozedUntil"]>"2026-11-07")
               for r in our_action)
assert all_pages>=4 and active_pages>=4
cursor=get(queue+"/page",filter="ACTIVE",limit=50)["nextCursor"]
call(queue+"/page?"+urlencode({"filter":"ALL","limit":50,"cursor":cursor}),expected=400)
for invalid in ({"filter":"WRONG"},{"filter":"ACTIVE","limit":0},
                {"filter":"ACTIVE","limit":101},
                {"filter":"ACTIVE","cursor":"not-a-valid-cursor"}):
    call(queue+"/page?"+urlencode(invalid),expected=400)
last=next(r for r in our_active if r["sourceKey"]=="V95-OPS-145")
assert last["id"] not in {r["id"] for r in active_rows[:100]}
assert call(f"/api/v1/operations/findings/{last['id']}/state","POST",
            {"state":"DONE"})["state"]=="DONE"
assert sql(f"""SELECT count(*) FROM ops_finding_audit
 WHERE tenant_id='{tenant}' AND finding_id='{last["id"]}'""")=="1"
active_after,_=traverse(queue+"/page",filter="ACTIVE")
assert not any(r["id"]==last["id"] for r in active_after)
assert any(r["id"]==last["id"] for r in traverse(queue+"/page",filter="ALL")[0])

# 225 distinct local COUNT_OBSERVATION -> REVIEWED EVIDENCE -> pending
# corrective proposals. Fixtures are not assertions that stock was released;
# the application will refuse self-approval and recheck inventory on any approval.
position=str(uuid4())
sql(f"""INSERT INTO erp_local_lot_position(
 id,tenant_id,request_key,receipt_id,warehouse_id,product_sku_id,
 bin_id,placed_quantity,remaining_quantity,reason,created_by)
 VALUES('{position}','{tenant}',gen_random_uuid(),'{batch[0]}',
 '{warehouse}','{batch[1]}','{bin_id}',1,1,
 'V95 synthetic nonposting evidence position','{actor}')""")
inserted=sql(f"""WITH seeds AS MATERIALIZED (
 SELECT i,gen_random_uuid() action_id,gen_random_uuid() review_id,
        gen_random_uuid() resolution_id
 FROM generate_series(1,225) i
), actions AS (
 INSERT INTO erp_local_lot_action(
  id,tenant_id,request_key,action_type,source_position_id,
  source_warehouse_id,source_bin_id,quantity,expected_remaining,
  observed_quantity,reason,actor_user_id)
 SELECT action_id,'{tenant}'::uuid,gen_random_uuid(),'COUNT_OBSERVATION',
 '{position}'::uuid,'{warehouse}'::uuid,'{bin_id}'::uuid,0,1,2,
 'V95 synthetic observed surplus only','{actor}'::uuid FROM seeds
 RETURNING id
), reviews AS (
 INSERT INTO erp_local_lot_count_review(
  id,tenant_id,request_key,observation_action_id,position_id,warehouse_id,
  product_sku_id,expected_bin_id,expected_position_quantity,observed_quantity,
  expected_on_hand,reason,status,submitted_by,reviewed_by,reviewed_at,review_reason)
 SELECT s.review_id,'{tenant}'::uuid,gen_random_uuid(),s.action_id,
 '{position}'::uuid,'{warehouse}'::uuid,'{batch[1]}'::uuid,'{bin_id}'::uuid,
 1,2,0,'V95 synthetic reviewed physical count','EVIDENCE_REQUIRED',
 '{actor}'::uuid,'{actor}'::uuid,now(),'V95 synthetic reviewed evidence'
 FROM seeds s JOIN actions a ON a.id=s.action_id
 RETURNING id
), resolutions AS (
 INSERT INTO erp_local_lot_exception_resolution(
  id,tenant_id,request_key,resolution_type,count_review_id,source_receipt_id,
  position_id,warehouse_id,product_sku_id,expected_bin_id,
  expected_position_quantity,expected_on_hand,adjustment_quantity,
  evidence_reference,reason,status,proposed_by,created_at)
 SELECT s.resolution_id,'{tenant}'::uuid,gen_random_uuid(),'RELEASED_SURPLUS_BIND',
  s.review_id,'{batch[0]}'::uuid,'{position}'::uuid,'{warehouse}'::uuid,
  '{batch[1]}'::uuid,'{bin_id}'::uuid,1,0,1,
  'V95-MOCK-'||lpad(s.i::text,3,'0'),'V95 nonposting approval queue fixture',
  'PENDING_REVIEW','{actor}'::uuid,now()-s.i*interval '1 minute'
 FROM seeds s JOIN reviews r ON r.id=s.review_id
 RETURNING id
) SELECT count(*) FROM resolutions""")
assert inserted=="225",inserted

lot=f"/api/v1/purchasing/lot-exceptions/warehouses/{warehouse}"
assert len(call(lot))==200
pending,pending_pages=traverse(lot+"/page",filter="PENDING")
all_lot,all_lot_pages=traverse(lot+"/page",filter="ALL")
our_lot=[r for r in pending if r["evidenceReference"].startswith("V95-MOCK-")]
assert len(our_lot)==225 and pending_pages>=5 and all_lot_pages>=5
assert len({r["id"] for r in our_lot})==225
old=next(r for r in our_lot if r["evidenceReference"]=="V95-MOCK-225")
assert old["id"] not in {r["id"] for r in pending[:200]}
assert all(r["status"]=="PENDING_REVIEW" for r in pending)
lot_cursor=get(lot+"/page",filter="PENDING",limit=50)["nextCursor"]
for params in ({"filter":"ALL","limit":50,"cursor":lot_cursor},
               {"filter":"NOPE"},{"filter":"PENDING","limit":0},
               {"filter":"PENDING","limit":101},
               {"filter":"PENDING","cursor":"invalid"}):
    call(lot+"/page?"+urlencode(params),expected=400)
other=str(uuid4())
call(f"/api/v1/purchasing/lot-exceptions/warehouses/{other}/page?"+
     urlencode({"filter":"PENDING","limit":50,"cursor":lot_cursor}),expected=400)
# The original proposer cannot approve their own synthetic application,
# even if it appears on the fifth page. There must be no stock mutation.
call(f"/api/v1/purchasing/lot-exceptions/{old['id']}/decision",
     "POST",{"decision":"APPROVE","reason":"V95 same-owner approval must fail"},expected=409)
assert sql(f"""SELECT status FROM erp_local_lot_exception_resolution
 WHERE tenant_id='{tenant}' AND id='{old["id"]}'""")=="PENDING_REVIEW"
assert sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")==stock_before
assert sql(f"""SELECT count(*) FROM erp_purchase_order
 WHERE tenant_id='{tenant}'""")==po_before

print("PASS V95 PG18 Mock: 165 synthetic Ops findings (OPEN/ACK/DONE, "+
      "snooze ACTIONABLE) beyond 100, state action/audit on >100th, "+
      "225 lot corrective proposals beyond 200 across 5+ pages, "+
      "same-owner approval rejected, tenant/filter/limit cursors 400 and "+
      "auth 401, no stock/PO/Amazon writes")
