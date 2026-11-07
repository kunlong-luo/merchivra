#!/usr/bin/env python3
"""V94: QC return/finding workbench pages & whole-inspection totals, PG18 Mock ONLY.

Run after V92 and V93 purchasing fixture scripts on disposable localhost PG18.
No Amazon production authorization/requests or remote writes.
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
    assert p.returncode == 0, (query[:250], p.stderr[-1600:])
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
        with http.open(Request("http://127.0.0.1:8080" + path,
            method=method, headers=headers, data=data), timeout=45) as response:
            status, raw, cache = (response.status, response.read(),
                                  response.headers.get("Cache-Control", ""))
    except HTTPError as exc:
        status, raw, cache = exc.code, exc.read(), ""
    assert status == expected, (method, path, status, expected, raw[:700])
    if status == 200 and method == "GET" and (
        "/page?" in path or path.endswith("/returns/summary")):
        assert "no-store" in cache, (path, cache)
    try:
        return json.loads(raw)
    except (TypeError, ValueError):
        return raw

def get(path, **query):
    return call(path + "?" + urlencode(query))

def traverse(path, **filters):
    rows, cursor, pages = [], None, 0
    while True:
        query = {"limit": 50, **filters}
        if cursor:
            query["cursor"] = cursor
        value = get(path, **query)
        pages += 1
        assert pages < 30 and value["pageSize"] == 50
        assert len(value["items"]) <= 50
        rows.extend(value["items"])
        if not value["hasMore"]:
            assert value["nextCursor"] is None
            return rows, pages
        assert value["nextCursor"] and len(value["items"]) == 50
        cursor = value["nextCursor"]

call("/api/v1/purchasing/quality/findings/page?limit=50", expected=401)
csrf = call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login", "POST", {
    "username": os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password": os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]}, form=True)
csrf = call("/api/v1/auth/csrf")["token"]
me = call("/api/v1/auth/me")
assert me["isOwner"]
tenant, actor = me["activeTenantId"], me["userId"]
warehouse = sql(f"""SELECT id FROM erp_local_warehouse WHERE tenant_id='{tenant}'
 AND warehouse_code='V92PAGEWAREHOUSE'""")
po = sql(f"""SELECT id FROM erp_purchase_order WHERE tenant_id='{tenant}'
 AND order_number='V92-PAGED-FIXTURE'""")
supplier = sql(f"""SELECT id FROM erp_supplier WHERE tenant_id='{tenant}'
 AND supplier_code='V91SUPPLIER'""")
sku = sql(f"""SELECT id FROM product_sku WHERE tenant_id='{tenant}'
 AND internal_sku='V92-PAGED-512'""")
line = sql(f"""SELECT id FROM erp_purchase_order_line WHERE tenant_id='{tenant}'
 AND purchase_order_id='{po}' AND product_sku_id='{sku}'""")
assert all((warehouse,po,supplier,sku,line)), "requires V92/93 fixture"

# A legitimate new V46 pending receipt inspected through the authenticated HTTP
# endpoint. Its rejected quantity creates quarantined (non-saleable) units.
receipt = str(uuid4())
sql(f"""INSERT INTO erp_purchase_receipt(
 id,tenant_id,purchase_order_id,purchase_order_line_id,quantity,received_by,warehouse_id)
 VALUES('{receipt}','{tenant}','{po}','{line}',220,'{actor}','{warehouse}')""")
sql(f"""INSERT INTO erp_receipt_quality_batch(
 tenant_id,receipt_id,warehouse_id,product_sku_id,received_quantity,lot_code,received_by)
 VALUES('{tenant}','{receipt}','{warehouse}','{sku}',220,'V94-QC-RETURNS','{actor}')""")
stock_before = sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")
po_before = sql(f"""SELECT COUNT(*) FROM erp_purchase_order WHERE tenant_id='{tenant}'""")
qc = call(f"/api/v1/purchasing/quality/receipts/{receipt}/inspect","POST",{
    "requestKey":str(uuid4()),"acceptedQuantity":20,"rejectedQuantity":200,
    "reason":"V94 disposable inspection of 200 rejected cartons"},expected=201)
assert qc["rejectedQuantity"] == 200 and qc["receiptId"] == receipt
inspection = qc["id"]
returns = f"/api/v1/purchasing/quality/inspections/{inspection}/returns"
assert call(returns + "/summary") == {
    "rejectedUnits":200,"draftedUnits":0,"dispatchedUnits":0,"unallocatedUnits":200}
call(f"/api/v1/purchasing/receipt-batches/{receipt}/release","POST",expected=409)

# 125 distinct local returns, 70 DRAFT + 35 DISPATCHED + 20 CANCELLED.
# Old LIMIT 100 hides actionable drafts whose timestamp is oldest.
sql(f"""INSERT INTO erp_supplier_return(
 id,tenant_id,request_key,inspection_id,supplier_id,quantity,reference,
 status,created_by,created_at,dispatched_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),'{inspection}'::uuid,
 '{supplier}'::uuid,1,'V94-RMA-'||lpad(i::text,3,'0'),
 CASE WHEN i<=70 THEN 'DRAFT' WHEN i<=105 THEN 'DISPATCHED'
      ELSE 'CANCELLED' END,'{actor}'::uuid,
 now() - ((125-i)*interval '1 minute'),
 CASE WHEN i>70 AND i<=105 THEN now() ELSE NULL END
 FROM generate_series(1,125) i""")
assert len(call(returns)) == 100
rows,pages = traverse(returns+"/page")
assert len(rows)==125 and pages==3
assert len({r["id"] for r in rows})==125
assert {r["status"] for r in rows}=={"DRAFT","DISPATCHED","CANCELLED"}
assert call(returns + "/summary") == {
    "rejectedUnits":200,"draftedUnits":70,"dispatchedUnits":35,"unallocatedUnits":95}
old_page_draft = next(r for r in rows[100:] if r["status"]=="DRAFT")
other = str(uuid4())
call(f"/api/v1/purchasing/quality/inspections/{other}/returns/page?"+
     urlencode({"limit":50,"cursor":get(returns+"/page",limit=50)["nextCursor"]}),
     expected=400)
call(f"/api/v1/purchasing/quality/inspections/{other}/returns/summary",expected=404)
for invalid in ({"limit":0},{"limit":101},{"cursor":"bad%"},{"cursor":"a"*2100}):
    call(returns+"/page?"+urlencode(invalid),expected=400)

# An actual human action on a record beyond the old 100-row cap; no stock posting.
dispatched=call("/api/v1/purchasing/quality/returns/"+old_page_draft["id"]+
                "/dispatch","POST")
assert dispatched["status"]=="DISPATCHED"
assert call(returns+"/summary")=={
    "rejectedUnits":200,"draftedUnits":69,"dispatchedUnits":36,"unallocatedUnits":95}
cancel_draft=next(r for r in rows[100:] if r["status"]=="DRAFT"
                  and r["id"]!=old_page_draft["id"])
cancelled=call("/api/v1/purchasing/quality/returns/"+cancel_draft["id"]+
               "/cancel","POST")
assert cancelled["status"]=="CANCELLED"
assert call(returns+"/summary")=={
    "rejectedUnits":200,"draftedUnits":68,"dispatchedUnits":36,"unallocatedUnits":96}
request={"requestKey":str(uuid4()),"quantity":2,"reference":"V94-RMA-CREATED"}
created=call(returns,"POST",request,201)
assert created["status"]=="DRAFT"
assert call(returns,"POST",request,201)["id"]==created["id"]
assert call(returns+"/summary")=={
    "rejectedUnits":200,"draftedUnits":70,"dispatchedUnits":36,"unallocatedUnits":94}
assert len(traverse(returns+"/page")[0])==126

# Populate 65 additional authentic receipt QC records and 2 findings each.
# These SQL inserts are disposable test fixtures; GETs and review state changes
# must not alter stock ledgers or automatically dispatch supplier returns.
created_qc=sql(f"""WITH sample AS (
 SELECT receipt_id,warehouse_id,product_sku_id,received_quantity
 FROM erp_receipt_quality_batch WHERE tenant_id='{tenant}'
   AND status='PENDING_QC' AND lot_code LIKE 'V93-QC-%'
 ORDER BY lot_code LIMIT 65
), added AS (
 INSERT INTO erp_purchase_receipt_qc(
 id,tenant_id,request_key,purchase_receipt_id,warehouse_id,
 product_sku_id,inspected_quantity,accepted_quantity,rejected_quantity,reason,inspected_by)
 SELECT gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),receipt_id,
 warehouse_id,product_sku_id,received_quantity,received_quantity-1,1,
 'V94 synthetic local findings fixture','{actor}'::uuid FROM sample
 RETURNING id,purchase_receipt_id
) SELECT count(*) FROM added""")
assert created_qc == "65", created_qc
updated=sql(f"""WITH changed AS (
 UPDATE erp_receipt_quality_batch b
 SET status='INSPECTED',inspection_id=q.id,updated_at=now()
 FROM erp_purchase_receipt_qc q WHERE b.tenant_id='{tenant}'::uuid
 AND q.tenant_id=b.tenant_id AND q.purchase_receipt_id=b.receipt_id
 AND q.reason='V94 synthetic local findings fixture'
 RETURNING b.receipt_id
) SELECT count(*) FROM changed""")
assert updated=="65",updated
sql(f"""INSERT INTO erp_purchase_quality_finding(
 id,tenant_id,inspection_id,rule_code,state,last_scan_id,last_seen_at,changed_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,q.id,kind,
 'OPEN',gen_random_uuid(),now(),now() -
   (row_number() OVER (ORDER BY q.id,kind))*interval '1 minute'
 FROM erp_purchase_receipt_qc q
 CROSS JOIN (VALUES ('QC_REJECTED_PENDING'),('QC_HOLD_AGED')) AS t(kind)
 WHERE q.tenant_id='{tenant}' AND q.reason='V94 synthetic local findings fixture'""")
findings="/api/v1/purchasing/quality/findings"
assert len(call(findings+"?filter=ACTIVE"))==100
active,pages=traverse(findings+"/page",filter="ACTIVE")
assert len(active)==130 and pages==3, (len(active),pages)
assert len({f["id"] for f in active})==130
assert all(f["state"]=="OPEN" for f in active)
all_rows,_=traverse(findings+"/page",filter="ALL")
assert len(all_rows)==130
page1=get(findings+"/page",limit=50,filter="ACTIVE")
call(findings+"/page?"+urlencode({
 "limit":50,"filter":"ALL","cursor":page1["nextCursor"]}),expected=400)
for params in ({"limit":0,"filter":"ACTIVE"},{"limit":101,"filter":"ACTIVE"},
               {"filter":"WRONG"},{"filter":"ACTIVE","cursor":"bad%"}):
    call(findings+"/page?"+urlencode(params),expected=400)

last=active[-1]
assert call(f"/api/v1/purchasing/quality/findings/{last['id']}/state",
            "POST",{"state":"ACKNOWLEDGED"})["state"]=="ACKNOWLEDGED"
assert call(f"/api/v1/purchasing/quality/findings/{last['id']}/state",
            "POST",{"state":"DONE"})["state"]=="DONE"
active_after,_=traverse(findings+"/page",filter="ACTIVE")
all_after,_=traverse(findings+"/page",filter="ALL")
assert len(active_after)==129 and len(all_after)==130
assert next(f for f in all_after if f["id"]==last["id"])["state"]=="DONE"
assert sql(f"""SELECT count(*) FROM erp_purchase_quality_finding_audit
 WHERE tenant_id='{tenant}' AND finding_id='{last["id"]}'""")=="2"
assert sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")==stock_before
assert sql(f"""SELECT COUNT(*) FROM erp_purchase_order
 WHERE tenant_id='{tenant}'""")==po_before
print("PASS V94 PG18 Mock: 125->126 returns (3 pages), >100th DRAFT dispatch/cancel, "
      "all-inspection allocation=94 remaining; 130 findings (3 pages), "
      "last finding ACKNOWLEDGED/DONE audit, tenant/filter/cursor 400/401/404, "
      "independent QC release gate, no new stock/PO/Amazon writes")
