#!/usr/bin/env python3
"""V97: disposable PG18 Mock, full supplier follow-up/score/settlement pages.

The synthetic SPAPI_FINANCES/LIVE rows below exist ONLY in an isolated local
database to exercise source filtering. They are NOT external Amazon evidence.
No real OAuth/SP-API or remote writes, no bank payment or ledger posting.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, HTTPCookieProcessor, build_opener
from uuid import uuid4

assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES","false")=="false"

client=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=""
def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    r=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
      "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",statement],
      capture_output=True,text=True,env=env,check=False)
    assert r.returncode==0,(statement[:380],r.stderr[-1800:])
    return r.stdout.strip()

def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and csrf:
        headers["X-CSRF-TOKEN"]=csrf
    try:
        with client.open(Request("http://127.0.0.1:8080"+path,method=method,
            headers=headers,data=data),timeout=70) as r:
            status,raw,cache=r.status,r.read(),r.headers.get("Cache-Control","")
    except HTTPError as e:
        status,raw,cache=e.code,e.read(),""
    assert status==expected,(method,path,status,expected,raw[:800])
    if status==200 and "/page?" in path:
        assert "no-store" in cache,(path,cache)
    try:return json.loads(raw)
    except (ValueError,TypeError):return raw

def get(path,**args):
    return call(path+"?"+urlencode(args))

def traverse(path,**args):
    items,cursor,pages=[],None,0
    while True:
        params={"limit":50,**args}
        if cursor:params["cursor"]=cursor
        doc=get(path,**params)
        pages+=1
        assert pages<35 and doc["pageSize"]==50 and len(doc["items"])<=50
        items.extend(doc["items"])
        if not doc["hasMore"]:
            assert doc["nextCursor"] is None
            return items,pages
        assert len(doc["items"])==50 and doc["nextCursor"]
        cursor=doc["nextCursor"]

scorebase="/api/v1/purchasing/supplier-performance"
call(scorebase+"/scorecards/page?limit=50",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me")
assert me["isOwner"]
tenant,actor=me["activeTenantId"],me["userId"]
market=sql(f"""SELECT id FROM seller_marketplace WHERE tenant_id='{tenant}'
 ORDER BY id LIMIT 1""")
currency=sql(f"""SELECT trim(currency_code) FROM seller_marketplace
 WHERE tenant_id='{tenant}' AND id='{market}'""")
assert market and currency and len(currency)==3
settlebase=f"/api/v1/finance/marketplaces/{market}/settlements"
po=sql(f"""SELECT id FROM erp_purchase_order WHERE tenant_id='{tenant}'
 AND order_number='V92-PAGED-FIXTURE'""")
assert po
stock_before=sql(f"SELECT count(*) FROM erp_local_stock_movement WHERE tenant_id='{tenant}'")
orders_before=sql(f"SELECT count(*) FROM erp_purchase_order WHERE tenant_id='{tenant}'")

# Supplier evidence scoring must not mask the 201st active supplier.
added=sql(f"""WITH inserted AS (
 INSERT INTO erp_supplier(id,tenant_id,supplier_code,display_name)
 SELECT gen_random_uuid(),'{tenant}'::uuid,
   'V97-SUP-'||lpad(i::text,4,'0'),
   'V97 Mock Supplier '||lpad(i::text,4,'0')
 FROM generate_series(1,225) i RETURNING id
) SELECT count(*) FROM inserted""")
assert added=="225"
assert len(call(scorebase+"/scorecards"))==200
scorecards,score_pages=traverse(scorebase+"/scorecards/page")
ours=[s for s in scorecards if s["supplierName"].startswith("V97 Mock Supplier ")]
assert len(ours)==225 and score_pages>=5
assert len({s["supplierId"] for s in ours})==225
assert all(s["evidenceScore"] is None and s["scoringStatus"]=="INSUFFICIENT_EVIDENCE"
 for s in ours)

# The existing disposable V92 PO has 441 independent lines, but V92 did not
# assign an approved_at timestamp. Make this old fixture complete *locally*
# before creating action-ready followups; never approve a real PO.
sql(f"""UPDATE erp_purchase_order SET approved_at=now()-interval '9 days'
 WHERE tenant_id='{tenant}' AND id='{po}' AND approved_at IS NULL""")
follow_added=sql(f"""WITH lines AS (
 SELECT l.id,row_number() OVER (ORDER BY l.id) n
 FROM erp_purchase_order_line l WHERE l.tenant_id='{tenant}'
 AND l.purchase_order_id='{po}' ORDER BY l.id LIMIT 180
), inserted AS (
 INSERT INTO erp_purchase_followup_finding(
 id,tenant_id,purchase_order_line_id,rule_code,last_scan_id,state,changed_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,id,'ASN_COVERAGE_GAP',
 gen_random_uuid(),CASE WHEN n<=130 THEN 'OPEN'
   WHEN n<=160 THEN 'ACKNOWLEDGED' ELSE 'DONE' END,
 now()-n*interval '1 minute'
 FROM lines RETURNING id
) SELECT count(*) FROM inserted""")
assert follow_added=="180"
assert len(call(scorebase+"/followups?filter=ALL"))==100
followups,follow_pages=traverse(scorebase+"/followups/page",filter="ALL")
active,active_pages=traverse(scorebase+"/followups/page",filter="ACTIVE")
ours_f=[f for f in followups if f["orderId"]==po]
assert len(ours_f)==180 and follow_pages>=4
assert len({f["id"] for f in ours_f})==180
assert len([f for f in active if f["orderId"]==po])==160
assert active_pages>=4
late=next(f for f in ours_f[100:] if f["state"]=="OPEN")
changed=call(scorebase+"/followups/"+late["id"]+"/state","POST",{"state":"ACKNOWLEDGED"})
assert changed["state"]=="ACKNOWLEDGED"
assert sql(f"""SELECT count(*) FROM erp_purchase_followup_audit
 WHERE tenant_id='{tenant}' AND finding_id='{late["id"]}'""")=="1"

# V30 manual statement is NOT a bank confirmation. 125 references share the
# same 8-day period and original marketplace currency.
statement_added=sql(f"""WITH inserted AS (
 INSERT INTO finance_settlement_statement(
 id,tenant_id,seller_marketplace_id,statement_ref,
 period_start,period_end,reported_net,currency_code,created_by,created_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{market}'::uuid,
  'V97STM-'||lpad(i::text,3,'0'),
  now()-interval '7 days',now()+interval '1 day',
  100.000000,'{currency}','{actor}'::uuid,
  now()-i*interval '1 minute'
 FROM generate_series(1,125) i RETURNING id
) SELECT count(*) FROM inserted""")
assert statement_added=="125"
assert len(call(settlebase))==50
statements,statement_pages=traverse(settlebase+"/page")
ours_s=[s for s in statements if s["reference"].startswith("V97STM-")]
assert len(ours_s)==125 and statement_pages>=3
assert all(s["evidenceOrigin"]=="MANUAL_UNVERIFIED" for s in ours_s)
target=next(s for s in ours_s if s["reference"]=="V97STM-125")
assert target not in ours_s[:50]
entry=f"{settlebase}/{target['id']}"
assert call(entry+"/preview")["bankVerified"] is False

# Fictional source records are injected into disposable PG18 only to exercise
# the existing trusted-source filter; no Amazon remote call was made.
created_events=sql(f"""WITH inserted AS (
 INSERT INTO finance_source_event(
 id,tenant_id,seller_marketplace_id,source_family,source_event_key,
 data_origin,category,amount,currency_code,posted_at,source_type)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{market}'::uuid,
  'SPAPI_FINANCES','V97-LOCAL-FICTION-'||lpad(i::text,4,'0'),
  'LIVE','UNCLASSIFIED',1.000000,'{currency}',
  now()-i*interval '12 minutes','V97_SYNTHETIC_ONLY'
 FROM generate_series(1,170) i RETURNING id
) SELECT count(*) FROM inserted""")
assert created_events=="170"
matched=sql(f"""WITH inserted AS (
 INSERT INTO finance_transaction_reference(tenant_id,event_id,reference_kind,reference_value)
 SELECT tenant_id,id,'SETTLEMENT_ID','V97STM-125'
 FROM finance_source_event WHERE tenant_id='{tenant}'
 AND source_event_key BETWEEN 'V97-LOCAL-FICTION-0001' AND 'V97-LOCAL-FICTION-0065'
 RETURNING event_id
) SELECT count(*) FROM inserted""")
assert matched=="65"
assert len(call(entry+"/candidates"))==100
candidates,candidate_pages=traverse(entry+"/candidates/page")
ours_candidates=[s for s in candidates if s["sourceKey"].startswith("V97-LOCAL-FICTION-")]
assert len(ours_candidates)==170 and candidate_pages>=4
assert len({c["id"] for c in ours_candidates})==170
assert all(c["matchedReferenceKind"]=="SETTLEMENT_ID" for c in ours_candidates[:65])
assert all(c["matchedReferenceKind"] is None for c in ours_candidates[65:])

# Assign a source beyond the first 100 candidates, verify exclusion and
# idempotency, then undo it; no bank or Amazon state changes.
late_c=ours_candidates[145]
call(entry+"/events/"+late_c["id"],"POST",expected=204)
call(entry+"/events/"+late_c["id"],"POST",expected=204)
linked=get(entry+"/candidates/page",limit=50)
assert linked["pageSize"]==50
assert late_c["id"] not in {c["id"] for c in traverse(entry+"/candidates/page")[0]}
call(entry+"/events/"+late_c["id"],"DELETE",expected=204)
assert late_c["id"] in {c["id"] for c in traverse(entry+"/candidates/page")[0]}
call(entry+"/events/"+late_c["id"],"DELETE",expected=404)

# Generate 104 actual link/unlink audit events (not fake audit entries).
for c in ours_candidates[:52]:
    path=entry+"/events/"+c["id"]
    call(path,"POST",expected=204)
    call(path,"DELETE",expected=204)
assert len(call(entry+"/audit"))==100
audits,audit_pages=traverse(entry+"/audit/page")
assert len(audits)==106 and audit_pages==3,(len(audits),audit_pages)
assert sum(a["action"]=="EVENT_LINKED" for a in audits)==53
assert sum(a["action"]=="EVENT_UNLINKED" for a in audits)==53
assert all(a["actorId"]==actor for a in audits)
assert call(entry+"/preview")["bankVerified"] is False

# All cursor scopes include tenant; finance pages also include marketplace,
# statement, source type, and match priority. Wrong scopes fail closed.
first=get(scorebase+"/followups/page",filter="ALL",limit=50)
follow_cursor=first["nextCursor"]
assert follow_cursor
get(scorebase+"/scorecards/page",limit=50)
call(scorebase+"/followups/page?"+urlencode({
 "filter":"ACTIVE","limit":50,"cursor":follow_cursor}),expected=400)
c_cursor=get(entry+"/candidates/page",limit=50)["nextCursor"]
assert c_cursor
other=next(s for s in ours_s if s["id"]!=target["id"])
call(f"{settlebase}/{other['id']}/candidates/page?"+urlencode({
 "limit":50,"cursor":c_cursor}),expected=400)
call(f"{settlebase}/{other['id']}/audit/page?limit=50",expected=200)
s_cursor=get(settlebase+"/page",limit=50)["nextCursor"]
call("/api/v1/finance/marketplaces/"+str(uuid4())+
 "/settlements/page?"+urlencode({"limit":50,"cursor":s_cursor}),expected=404)
call(entry+"/audit/page?limit=0",expected=400)
call(entry+"/audit/page?limit=101",expected=400)
call(scorebase+"/followups/page?filter=WRONG&limit=50",expected=400)
call(scorebase+"/scorecards/page?limit=50&cursor=bad%25",expected=400)
assert sql(f"SELECT count(*) FROM erp_local_stock_movement WHERE tenant_id='{tenant}'")==stock_before
assert sql(f"SELECT count(*) FROM erp_purchase_order WHERE tenant_id='{tenant}'")==orders_before
print("PASS V97 PG18 Mock: 225 supplier scorecards beyond 200; 180 followups beyond "+
 "100 (late state change/audit, ACTIVE/ALL); 125 manual statements beyond 50; "+
 "170 local-fiction LIVE-only source candidates beyond 100 ranked across "+
 "matched/unmatched; >100 real link/unlink audits; scoped cursors/401; "+
 "no stock/order/Amazon/bank writes")
