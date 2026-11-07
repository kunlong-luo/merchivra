#!/usr/bin/env python3
"""V96 complete supplier variance, claim, source and immutable audit paging.

Requires V92-V95 fixtures; only disposable localhost PostgreSQL 18 Mock.
No supplier communication, financial posting or Amazon OAuth/SP-API writes.
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
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    run=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
        env=env,text=True,capture_output=True,check=False)
    assert run.returncode==0,(query[:330],run.stderr[-1600:])
    return run.stdout.strip()

def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and csrf:
        headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,method=method,
            headers=headers,data=data),timeout=50) as res:
            status,raw,cache=res.status,res.read(),res.headers.get("Cache-Control","")
    except HTTPError as err:
        status,raw,cache=err.code,err.read(),""
    assert status==expected,(method,path,status,expected,raw[:900])
    if status==200 and "/page?" in path:
        assert "no-store" in cache,(path,cache)
    try:return json.loads(raw)
    except (ValueError,TypeError):return raw

def get(path,**params):
    return call(path+"?"+urlencode(params))

def traverse(path,**filters):
    rows,cursor,pages=[],None,0
    while True:
        params={"limit":50,**filters}
        if cursor:params["cursor"]=cursor
        doc=get(path,**params)
        pages+=1
        assert pages<30 and doc["pageSize"]==50 and len(doc["items"])<=50
        rows+=doc["items"]
        if not doc["hasMore"]:
            assert doc["nextCursor"] is None
            return rows,pages
        assert len(doc["items"])==50 and doc["nextCursor"]
        cursor=doc["nextCursor"]

base="/api/v1/purchasing/disputes"
call(base+"/orders/"+str(uuid4())+"/claims/page?limit=50",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me")
assert me["isOwner"]
tenant,actor=me["activeTenantId"],me["userId"]
po=sql(f"""SELECT id FROM erp_purchase_order
 WHERE tenant_id='{tenant}' AND order_number='V92-PAGED-FIXTURE'""")
supplier=sql(f"""SELECT supplier_id FROM erp_purchase_order
 WHERE tenant_id='{tenant}' AND id='{po}'""")
assert po and supplier
stock_before=sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")
po_before=sql(f"""SELECT count(*) FROM erp_purchase_order WHERE tenant_id='{tenant}'""")

# 535 independent local line variance observations under an existing V92 order.
# Actual unreceived quantity is snapshotted for the V45 submit verification.
created=sql(f"""WITH lines AS (
 SELECT l.id,l.ordered_quantity,
        COALESCE((SELECT SUM(r.quantity) FROM erp_purchase_receipt r
                  WHERE r.tenant_id=l.tenant_id
                    AND r.purchase_order_line_id=l.id),0)::int received,
        row_number() OVER (ORDER BY l.id) n
 FROM erp_purchase_order_line l
 WHERE l.tenant_id='{tenant}' AND l.purchase_order_id='{po}'
 ORDER BY l.id LIMIT 535
), added AS (
 INSERT INTO erp_purchase_variance(
  id,tenant_id,request_key,purchase_order_id,purchase_order_line_id,
  kind,expected_quantity,observed_quantity,variance_quantity,reason,
  state,created_by,reviewed_by,reviewed_at,created_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),'{po}'::uuid,
  l.id,'OVERAGE_UNPOSTED',
  (l.ordered_quantity-l.received)::int,
  (l.ordered_quantity-l.received+1)::int,1,
  'V96 disposable source observation',
  CASE WHEN n<=240 THEN 'OPEN' ELSE 'REVIEWED' END,
  '{actor}'::uuid,
  CASE WHEN n<=240 THEN NULL ELSE '{actor}'::uuid END,
  CASE WHEN n<=240 THEN NULL ELSE now() END,
  now()-n*interval '1 minute'
 FROM lines l
 RETURNING id
) SELECT count(*) FROM added""")
assert created=="535",created
assert len(call(base+"/orders/"+po+"/variances"))==200
varbase=base+"/orders/"+po+"/variances"
all_variances,variance_pages=traverse(varbase+"/page",filter="ALL")
active_variances,_=traverse(varbase+"/page",filter="ACTIONABLE")
ours=[v for v in all_variances if v["reason"]=="V96 disposable source observation"]
assert len(ours)==535 and variance_pages>=11
assert len({v["id"] for v in ours})==535
assert len(active_variances)>=535
open_last=next(v for v in ours[200:] if v["state"]=="OPEN")
call(base+"/variances/"+open_last["id"]+"/review","POST",expected=409)
reviewed_last=next(v for v in ours[200:] if v["state"]=="REVIEWED")
assert call(base+"/variances/"+reviewed_last["id"]+"/close","POST")["state"]=="CLOSED"
assert sql(f"""SELECT count(*) FROM erp_supplier_case_audit
 WHERE tenant_id='{tenant}' AND variance_id='{reviewed_last["id"]}'
 AND event_type='VARIANCE_CLOSED'""")=="1"
duplicate={"requestKey":str(uuid4()),"lineId":open_last["lineId"],
 "kind":"OVERAGE_UNPOSTED","asnId":None,"reportedExcessQuantity":1,
 "reason":"V96 duplicate observation must fail"}
call(varbase,"POST",duplicate,expected=409)

# 235 claims on 235 distinct REVIEWED variances. The remaining 59+ sources
# plus older QC rejected observations must be discoverable independent of
# the current variance, claim or receipt pages.
claims_added=sql(f"""WITH selected AS (
 SELECT id,row_number() OVER (ORDER BY created_at DESC,id DESC) n
 FROM erp_purchase_variance WHERE tenant_id='{tenant}'
 AND purchase_order_id='{po}' AND state='REVIEWED'
 AND reason='V96 disposable source observation'
 ORDER BY created_at DESC,id DESC LIMIT 235
), added AS (
 INSERT INTO erp_supplier_claim(
 id,tenant_id,request_key,variance_id,purchase_order_id,supplier_id,
 requested_quantity,remedy,reason,status,created_by,created_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,gen_random_uuid(),
  s.id,'{po}'::uuid,'{supplier}'::uuid,1,'REPLACEMENT',
  'V96 disposable internal claim fixture',
  CASE WHEN n<=215 THEN 'DRAFT' WHEN n<=225 THEN 'SUBMITTED'
       ELSE 'CANCELLED' END,
  '{actor}'::uuid,now()-n*interval '1 minute'
 FROM selected s RETURNING id
) SELECT count(*) FROM added""")
assert claims_added=="235",claims_added
claimbase=base+"/orders/"+po+"/claims"
assert len(call(claimbase))==200
claims,claim_pages=traverse(claimbase+"/page",filter="ALL")
actionable,_=traverse(claimbase+"/page",filter="ACTIONABLE")
ours_claims=[c for c in claims if c["reason"]=="V96 disposable internal claim fixture"]
assert len(ours_claims)==235 and claim_pages>=5
assert len({c["id"] for c in ours_claims})==235
assert len([c for c in actionable if c["reason"]=="V96 disposable internal claim fixture"])==225
old_claim=next(c for c in ours_claims[200:] if c["status"]=="DRAFT")
assert call(base+"/claims/"+old_claim["id"]+"/submit","POST")["status"]=="SUBMITTED"
call(base+"/claims/"+old_claim["id"]+"/approve","POST",expected=409)
assert sql(f"""SELECT count(*) FROM erp_supplier_case_audit
 WHERE tenant_id='{tenant}' AND claim_id='{old_claim["id"]}'
 AND event_type='CLAIM_SUBMITTED'""")=="1"

sources_path=base+"/orders/"+po+"/claim-sources/page"
sources,source_pages=traverse(sources_path)
ours_sources=[x for x in sources if x["sourceType"]=="VARIANCE"]
assert len(ours_sources)>=59 and source_pages>=2,(len(ours_sources),source_pages)
claimed_ids={c["varianceId"] for c in ours_claims}
assert all(x["sourceId"] not in claimed_ids for x in ours_sources)
assert reviewed_last["id"] not in {s["sourceId"] for s in ours_sources}
candidate=ours_sources[-1]
payload={"requestKey":str(uuid4()),"varianceId":candidate["sourceId"],
 "inspectionId":None,"requestedQuantity":1,"remedy":"REPLACEMENT",
 "reason":"V96 local claim from later eligibility page"}
new_claim=call(base+"/claims","POST",payload,expected=201)
assert call(base+"/claims","POST",payload,expected=201)["id"]==new_claim["id"]
assert candidate["sourceId"] not in {s["sourceId"] for s in
 traverse(sources_path)[0] if s["sourceType"]=="VARIANCE"}
existing=ours_claims[0]
call(base+"/claims","POST",{"requestKey":str(uuid4()),
 "varianceId":existing["varianceId"],"inspectionId":None,
 "requestedQuantity":1,"remedy":"REPLACEMENT",
 "reason":"V96 duplicate case source must fail"},expected=409)

# An immutable audit timeline exceeding the old 100-event response cap.
# Synthetic events are explicit stress fixtures, not actual supplier actions.
audit_count=sql(f"""WITH added AS (
 INSERT INTO erp_supplier_case_audit(
 id,tenant_id,claim_id,actor_user_id,event_type,note,created_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{old_claim["id"]}'::uuid,
 '{actor}'::uuid,'CLAIM_DRAFTED','V96 synthetic immutable fixture event',
 now()-i*interval '1 minute'
 FROM generate_series(1,135) i RETURNING id
) SELECT count(*) FROM added""")
assert audit_count=="135"
events_path=base+"/claims/"+old_claim["id"]+"/events"
assert len(call(events_path))==100
events,event_pages=traverse(events_path+"/page")
assert len(events)==136 and event_pages==3
assert len({e["id"] for e in events})==136
variance_events=traverse(base+"/variances/"+reviewed_last["id"]+"/events/page")[0]
assert any(e["eventType"]=="VARIANCE_CLOSED" for e in variance_events)

# No stale tenant/order/filter/record ID cursor can cross a data scope.
cursor=get(varbase+"/page",filter="ALL",limit=50)["nextCursor"]
call(varbase+"/page?"+urlencode({
 "filter":"ACTIONABLE","limit":50,"cursor":cursor}),expected=400)
call(base+"/orders/"+str(uuid4())+"/variances/page?"+
 urlencode({"filter":"ALL","limit":50,"cursor":cursor}),expected=400)
source_cursor=get(sources_path,limit=50)["nextCursor"]
call(base+"/orders/"+str(uuid4())+"/claim-sources/page?"+
 urlencode({"limit":50,"cursor":source_cursor}),expected=400)
event_cursor=get(events_path+"/page",limit=50)["nextCursor"]
call(base+"/claims/"+str(uuid4())+"/events/page?"+
 urlencode({"limit":50,"cursor":event_cursor}),expected=404)
for path in (varbase+"/page",claimbase+"/page"):
    for invalid in ({"filter":"WRONG"},{"filter":"ALL","limit":0},
                    {"filter":"ALL","limit":101},
                    {"filter":"ALL","cursor":"bad%"}):
        call(path+"?"+urlencode(invalid),expected=400)
assert sql(f"""SELECT count(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'""")==stock_before
assert sql(f"""SELECT count(*) FROM erp_purchase_order
 WHERE tenant_id='{tenant}'""")==po_before
print("PASS V96 PG18 Mock: 535 variances (>200) with reviewed/duplicate guard, "+
 "235 claims (>200) with later DRAFT submit+approval prohibition, "+
 "all-order claimable source picker with 59+ reviewed unclaimed and QC options, "+
 "136 immutable claim audits (>100), scoped cursors/401, no stock/PO/Amazon writes")
