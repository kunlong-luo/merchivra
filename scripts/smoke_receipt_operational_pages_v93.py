#!/usr/bin/env python3
"""V93: full operational receipt batch release / audit / pressure pagination.

Requires V92 local fixture smoke before this script, in disposable PostgreSQL 18.
All inserted rows are LOCAL fixture SQL, not external Amazon API operations.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener
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
            method=method, headers=headers, data=data), timeout=50) as response:
            status, raw = response.status, response.read()
            cache = response.headers.get("Cache-Control", "")
    except HTTPError as exc:
        status, raw, cache = exc.code, exc.read(), ""
    assert status == expected, (path, status, expected, raw[:700])
    if status == 200 and method == "GET" and ("/page?" in path or "/summary" in path):
        assert "no-store" in cache, (path, cache)
    try:
        return json.loads(raw)
    except (TypeError, ValueError):
        return raw

def get(path, **query):
    return call(path + "?" + urlencode(query))

def traverse(path):
    rows, cursor, page_count = [], None, 0
    while True:
        query = {"limit": 50}
        if cursor:
            query["cursor"] = cursor
        result = get(path, **query)
        page_count += 1
        assert page_count < 30 and result["pageSize"] == 50
        assert len(result["items"]) <= 50
        rows.extend(result["items"])
        if not result["hasMore"]:
            assert result["nextCursor"] is None
            break
        assert result["nextCursor"] and len(result["items"]) == 50
        cursor = result["nextCursor"]
    return rows, page_count

call("/api/v1/purchasing/receipt-batches/warehouses/" + str(uuid4()) +
     "/page?limit=50", expected=401)
csrf = call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login", "POST", {
    "username": os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password": os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]}, form=True)
csrf = call("/api/v1/auth/csrf")["token"]
me = call("/api/v1/auth/me")
assert me["isOwner"]
tenant, actor = me["activeTenantId"], me["userId"]
warehouse = sql(f"""SELECT id FROM erp_local_warehouse
    WHERE tenant_id='{tenant}' AND warehouse_code='V92PAGEWAREHOUSE'""")
po = sql(f"""SELECT id FROM erp_purchase_order
    WHERE tenant_id='{tenant}' AND order_number='V92-PAGED-FIXTURE'""")
assert warehouse and po, "V92 disposable fixture script must run first"

# Add 315 additional unique, tenant-scoped SKU receipts. At least 316 SKU
# pressure rows and 635 physical batch rows now exist in this warehouse.
inserted = sql(f"""WITH lines AS (
 INSERT INTO erp_purchase_order_line(
   id,tenant_id,purchase_order_id,product_sku_id,ordered_quantity,unit_cost)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{po}'::uuid,s.id,1,1.00
 FROM product_sku s WHERE s.tenant_id='{tenant}'
 AND s.internal_sku BETWEEN 'V92-PAGED-126' AND 'V92-PAGED-440'
 RETURNING id,product_sku_id
), receipts AS (
 INSERT INTO erp_purchase_receipt(
   id,tenant_id,purchase_order_id,purchase_order_line_id,quantity,received_by,warehouse_id)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{po}'::uuid,
   l.id,1,'{actor}'::uuid,'{warehouse}'::uuid FROM lines l
 RETURNING id,purchase_order_line_id
), batches AS (
 INSERT INTO erp_receipt_quality_batch(
   tenant_id,receipt_id,warehouse_id,product_sku_id,received_quantity,lot_code,received_by)
 SELECT '{tenant}'::uuid,r.id,'{warehouse}'::uuid,l.product_sku_id,1,
  'V93-QC-'||s.internal_sku,'{actor}'::uuid
 FROM receipts r JOIN lines l ON l.id=r.purchase_order_line_id
 JOIN product_sku s ON s.tenant_id='{tenant}' AND s.id=l.product_sku_id
 RETURNING receipt_id
) SELECT COUNT(*) FROM batches""")
assert inserted == "315", inserted

order_path = f"/api/v1/purchasing/receipt-batches/orders/{po}"
warehouse_path = f"/api/v1/purchasing/receipt-batches/warehouses/{warehouse}"
pressure_path = f"/api/v1/purchasing/lot-locations/warehouses/{warehouse}/pressure"
assert len(call(order_path)) == 300
assert len(call(warehouse_path)) == 300
order_rows, order_pages = traverse(order_path + "/page")
warehouse_rows, warehouse_pages = traverse(warehouse_path + "/page")
assert len(order_rows) == 635 and order_pages == 13
assert len(warehouse_rows) == 635 and warehouse_pages == 13
assert {b["receiptId"] for b in order_rows} == {
    b["receiptId"] for b in warehouse_rows}
assert len({b["receiptId"] for b in order_rows}) == 635
assert all(b["status"] == "PENDING_QC" for b in order_rows)
summary = call(order_path + "/summary")
assert summary == {
    "pendingReceivedUnits": 860,
    "awaitingReleaseAcceptedUnits": 0,
    "pendingBatchCount": 635,
    "awaitingReleaseBatchCount": 0}, summary
assert summary["pendingReceivedUnits"] == sum(
    b["receivedQuantity"] for b in order_rows)
assert len(call(pressure_path)) == 300
pressure, pressure_pages = traverse(pressure_path + "/page")
assert len(pressure) == 316 and pressure_pages == 7, (len(pressure), pressure_pages)
assert len({p["productSkuId"] for p in pressure}) == 316
assert "V92-PAGED-440" in {p["internalSku"] for p in pressure}
assert all(p["usableUnits"] >= 0 and p["pendingQcUnits"] > 0 for p in pressure)
assert sum(p["pendingQcUnits"] for p in pressure) == 860
assert sum(p["acceptedAwaitingReleaseUnits"] for p in pressure) == 0
assert sum(p["declaredAsnInTransitUnits"] for p in pressure) == 0

# Source scope, malformed cursors and invalid limits fail without revealing rows.
for path in [order_path, warehouse_path, pressure_path]:
    page = get(path + "/page", limit=50)
    invalid = str(uuid4())
    wrong = path.replace(po, invalid).replace(warehouse, invalid)
    call(wrong + "/page?" + urlencode({
        "cursor": page["nextCursor"], "limit": 50}), expected=400)
    for params in ({"limit": 0}, {"limit": 101}, {"cursor": "bad%"},
                   {"cursor": "a" * 2100}):
        call(path + "/page?" + urlencode(params), expected=400)

# More than 200 immutable receipt audit events must remain browseable.
selected = next(b["receiptId"] for b in order_rows if b["receivedQuantity"] == 226)
event_base = f"/api/v1/purchasing/receipt-batches/{selected}/events"
sql(f"""INSERT INTO erp_receipt_quality_batch_event(
 id,tenant_id,receipt_id,actor_user_id,event_type,
 old_lot_code,new_lot_code,note)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{selected}'::uuid,'{actor}'::uuid,
 'LOT_RELABELED','V93-ORIGINAL','V93-TEST-'||lpad(i::text,3,'0'),
 'V93 synthetic audit pagination event '||i
 FROM generate_series(1,225) AS i""")
assert len(call(event_base)) == 200
audit, audit_pages = traverse(event_base + "/page")
assert len(audit) == 225 and audit_pages == 5
assert len({a["id"] for a in audit}) == 225
assert all(a["eventType"] == "LOT_RELABELED" and a["actorUserId"] == actor
           for a in audit)
other = next(b["receiptId"] for b in order_rows if b["receiptId"] != selected)
call(f"/api/v1/purchasing/receipt-batches/{other}/events/page?" +
     urlencode({"cursor": get(event_base + "/page", limit=50)["nextCursor"],
                "limit": 50}), expected=400)
call(f"/api/v1/purchasing/receipt-batches/{uuid4()}/events/page?limit=50",
     expected=404)

# The inspection form was still using the original 300-row receipt list.
# It now must reach and inspect a real V46 QC-held receipt beyond row 300.
quality_path = f"/api/v1/purchasing/quality/orders/{po}/receipts"
assert len(call(quality_path)) == 300
quality_rows, quality_pages = traverse(quality_path + "/page")
assert len(quality_rows) == 760 and quality_pages == 16
assert len({r["id"] for r in quality_rows}) == 760
batch_ids = {b["receiptId"] for b in order_rows}
target = next(r for r in quality_rows[300:]
              if r["id"] in batch_ids and r["inspectionId"] is None)
assert quality_rows.index(target) >= 300
assert target["warehouseId"] == warehouse
call(f"/api/v1/purchasing/quality/orders/{uuid4()}/receipts/page?" +
     urlencode({"limit": 50,
                "cursor": get(quality_path + "/page", limit=50)["nextCursor"]}),
     expected=400)
for invalid in ({"limit": 0}, {"limit": 101}, {"cursor": "bad%"}):
    call(quality_path + "/page?" + urlencode(invalid), expected=400)
before_inspect_stock = sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")
key = str(uuid4())
body = {"requestKey": key, "acceptedQuantity": target["receivedQuantity"],
        "rejectedQuantity": 0, "reason": "V93 disposable full-row receipt inspection"}
inspection = call(f"/api/v1/purchasing/quality/receipts/{target['id']}/inspect",
                  "POST", body, expected=201)
assert inspection["receiptId"] == target["id"]
assert inspection["acceptedQuantity"] == target["receivedQuantity"]
assert call(f"/api/v1/purchasing/quality/receipts/{target['id']}/inspect",
            "POST", body, expected=201)["id"] == inspection["id"]
assert call(order_path + "/summary") == {
    "pendingReceivedUnits": 860-target["receivedQuantity"],
    "awaitingReleaseAcceptedUnits": target["receivedQuantity"],
    "pendingBatchCount": 634,
    "awaitingReleaseBatchCount": 1}
assert sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""") == before_inspect_stock
assert sql(f"""SELECT status FROM erp_receipt_quality_batch
 WHERE tenant_id='{tenant}' AND receipt_id='{target["id"]}'""") == "INSPECTED"

stock_before = sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")
po_before = sql(f"""SELECT COUNT(*) FROM erp_purchase_order
 WHERE tenant_id='{tenant}'""")
for path in (order_path + "/page", order_path + "/summary",
             warehouse_path + "/page", pressure_path + "/page", event_base + "/page"):
    call(path + ("" if path.endswith("/summary") else "?limit=50"))
assert sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""") == stock_before
assert sql(f"""SELECT COUNT(*) FROM erp_purchase_order
 WHERE tenant_id='{tenant}'""") == po_before
print("PASS V93 PG18 local Mock: 635 actionable receipt batches (13 pages), "
      "full-order QC quantity=860, 316 SKU pressure rows, "
      "760 inspectable receipts, 225 immutable audit events, scoped cursors, "
      "independent Owner release still required, no stock/PO/Amazon writes")
