#!/usr/bin/env python3
"""V89 full-table business data-quality overview + safe export over HTTP/PG18.
Only disposable localhost Mock schema. No Amazon OAuth/SP-API or external writes.
"""
import csv
import http.cookiejar
import io
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,HTTPCookieProcessor,build_opener
from uuid import uuid4

assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"

def sql(query):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    p=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
        capture_output=True,text=True,env=env,check=False)
    assert p.returncode==0,p.stderr[-1400:]
    return p.stdout.strip()

def browser():
    return build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
owner=browser()
staff=browser()
csrf={}
def call(path,client=owner,method="GET",body=None,expected=200,form=False,
         text_response=False):
    headers={"Accept":"text/csv" if text_response else "application/json"}
    payload=None
    if body is not None:
        payload=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method=="POST" and csrf.get(id(client)):
        headers["X-CSRF-TOKEN"]=csrf[id(client)]
    try:
        with client.open(Request("http://127.0.0.1:8080"+path,method=method,
                 headers=headers,data=payload),timeout=40) as resp:
            status,data=resp.status,resp.read()
            cache=resp.headers.get("Cache-Control","")
            disposition=resp.headers.get("Content-Disposition","")
    except HTTPError as exc:
        status,data=exc.code,exc.read();cache="";disposition=""
    assert status==expected,(method,path,status,expected,data[:800])
    if status==200:assert "no-store" in cache,(path,cache)
    if text_response and status==200:
        assert "attachment" in disposition,disposition
        return data.decode("utf-8-sig")
    try:return json.loads(data)
    except (ValueError,TypeError):return data

def login(client,username,password):
    csrf[id(client)]=call("/api/v1/auth/csrf",client=client)["token"]
    call("/api/v1/auth/login",client=client,method="POST",
         body={"username":username,"password":password},form=True)
    csrf[id(client)]=call("/api/v1/auth/csrf",client=client)["token"]
    return call("/api/v1/auth/me",client=client)

call("/api/v1/business-overview",expected=401)
call("/api/v1/business-overview/export.csv",expected=401)
me=login(owner,os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"])
tenant=me["activeTenantId"]
actor=me["userId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
  WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp
first=call("/api/v1/business-overview")
assert not first["sourceSnapshotFrozen"] and not first["financialSourceComplete"]
assert not first["bankVerified"] and not first["ledgerPosted"]
assert not first["crossCurrencyTotalsAvailable"]
site=next(x for x in first["marketplaces"] if x["id"]==mp)
assert site["currency"] in ("USD","CAD","EUR","GBP","JPY","AUD","MXN","BRL","SGD")
assert site["listings"]["total"]>=64
assert site["orders"]["total"]>=64
assert site["inventory"]["skus"]>=64
assert site["inventory"]["unknown"]>=2
assert site["inventory"]["stale"]>=1
assert site["inventory"]["observedZeroFulfillable"]>=1
for subject,size in ((site["listings"],"total"),(site["orders"],"total"),
                     (site["inventory"],"skus")):
    assert sum(subject["sources"].values())==subject[size],subject
inv=site["inventory"]
assert sum(inv[k] for k in ("observed","partial","unknown","stale"))==inv["skus"]

# Exact counts from full canonical tables, never the first 50 UI rows.
listing_count=int(sql(f"""SELECT count(*) FROM marketplace_listing
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'"""))
orders_count=int(sql(f"""SELECT count(*) FROM sales_order
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'"""))
inventory_count=int(sql(f"""SELECT count(DISTINCT seller_sku_raw)
 FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'"""))
assert site["listings"]["total"]==listing_count,(site["listings"],listing_count)
assert site["orders"]["total"]==orders_count,(site["orders"],orders_count)
assert site["inventory"]["skus"]==inventory_count,(site["inventory"],inventory_count)
assert site["orders"]["itemsNotObserved"]==int(sql(f"""SELECT count(*)
 FROM sales_order WHERE tenant_id='{tenant}'
 AND seller_marketplace_id='{mp}' AND items_observation IS DISTINCT FROM 'OBSERVED'"""))
assert site["inventory"]["observedZeroFulfillable"]==int(sql(f"""
 SELECT count(*) FROM (
  SELECT DISTINCT ON (seller_sku_raw)
   quality,fulfillable FROM fba_inventory_observation
  WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'
  ORDER BY seller_sku_raw,observed_at DESC NULLS LAST,ingested_at DESC,id DESC
 ) latest WHERE quality='OBSERVED' AND fulfillable=0"""))

# Add a second scoped Marketplace with an entirely different original currency.
# Deliberately prefix its marketplace code with '=' to test reuse of V64's
# spreadsheet-formula-safe CSV encoder.
other_currency="GBP" if site["currency"]!="GBP" else "USD"
seller_account=sql(f"""SELECT seller_account_id FROM seller_marketplace
 WHERE tenant_id='{tenant}' AND id='{mp}'""")
second=str(uuid4())
sql(f"""INSERT INTO seller_marketplace(
 id,tenant_id,seller_account_id,marketplace_id,currency_code,timezone)
 VALUES('{second}','{tenant}','{seller_account}',
 '=V89-CURRENCY-TEST','{other_currency}','UTC')""")
sql(f"""INSERT INTO marketplace_listing(
 id,tenant_id,seller_marketplace_id,seller_sku,mapping_status,data_origin)
 VALUES(gen_random_uuid(),'{tenant}','{second}','V89-SKU','UNMAPPED','MOCK')""")
sql(f"""INSERT INTO sales_order(
 id,tenant_id,seller_marketplace_id,external_order_id,
 fulfillment_status_raw,financial_status,refund_status,items_observation,data_origin)
 VALUES(gen_random_uuid(),'{tenant}','{second}','V89-NO-PII-ORDER',
 'PENDING','NOT_OBSERVED','UNKNOWN','NOT_OBSERVED','MOCK')""")
different=call("/api/v1/business-overview")
assert different["mixedCurrencies"],different
other=next(x for x in different["marketplaces"] if x["id"]==second)
assert other["currency"]==other_currency and other["orders"]["total"]==1
assert other["orders"]["financeNotObserved"]==1
assert other["listings"]["total"]==1 and other["listings"]["unmapped"]==1
assert other["inventory"]["skus"]==0 and other["latestReport"] is None
assert other["orders"]["sources"]["mock"]==1
assert not different["crossCurrencyTotalsAvailable"]

# Reuse an already-frozen V64 order contribution; DO NOT recompute finances
# or add it to the GBP Marketplace row.
job=str(uuid4())
request_key=str(uuid4())
source_order=sql(f"""SELECT id FROM sales_order WHERE tenant_id='{tenant}'
 AND seller_marketplace_id='{mp}' ORDER BY id LIMIT 1""")
sql(f"""INSERT INTO finance_profit_report_job(
 request_key,id,tenant_id,seller_marketplace_id,created_by,
 period_after,period_before,currency_code,state,
 total_orders,processed_orders,created_at,completed_at)
 VALUES('{request_key}','{job}','{tenant}','{mp}','{actor}',
 DATE '2026-11-01',DATE '2026-11-04','{site['currency']}',
 'SUCCEEDED',1,1,now()+interval '10 minutes',now()+interval '10 minutes')""")
sql(f"""INSERT INTO finance_profit_report_order(
 tenant_id,job_id,order_id,cohort_position,state,readiness,currency_code,
 signed_sales,signed_amazon_fees,signed_fba_fees,provisional_cogs,
 estimated_contribution,extract_sha256,captured_at)
 VALUES('{tenant}','{job}','{source_order}',0,'ELIGIBLE',
 'REVIEWED_SOURCE_PREVIEW','{site['currency']}',
 120.000000,-10.000000,-5.000000,70.000000,35.000000,
 repeat('a',64),now())""")
ready=call("/api/v1/business-overview")
main=next(x for x in ready["marketplaces"] if x["id"]==mp)
report=main["latestReport"]
assert report["id"]==job and report["state"]=="SUCCEEDED"
assert report["contribution"]=="35.000000"
assert report["currency"]==site["currency"]
assert report["totalOrders"]==1 and report["processedOrders"]==1
assert report["eligibleOrders"]==1 and report["blockedOrders"]==0
assert report["cohortFrozen"] and not report["globalSourceSnapshot"]
assert not report["financialSourceComplete"] and not report["bankVerified"]
assert next(x for x in ready["marketplaces"] if x["id"]==second)["latestReport"] is None

# Safe current-quality CSV, independently refreshed, not an immutable
# V64 report. All rows retain original currency; no global profit column.
quality_csv=call("/api/v1/business-overview/export.csv",text_response=True)
reader=csv.DictReader(io.StringIO(quality_csv))
quality_rows=list(reader)
assert len(quality_rows)==len(ready["marketplaces"])
byid={r["marketplace_uuid"]:r for r in quality_rows}
assert byid[mp]["original_currency"]==site["currency"]
assert byid[mp]["v64_completed_contribution_only"]=="35.000000"
assert byid[second]["original_currency"]==other_currency
assert byid[second]["marketplace_code"]=="'=V89-CURRENCY-TEST"
assert byid[second]["v64_completed_contribution_only"]==""
assert byid[second]["orders"]=="1" and byid[second]["latest_fba_skus"]=="0"
assert byid[mp]["financial_source_complete"]=="false"
assert byid[mp]["bank_verified"]=="false"
assert byid[mp]["cross_currency_totals_available"]=="false"
assert "global_profit" not in reader.fieldnames
assert "estimated_net_profit" not in reader.fieldnames

# After a new unfinished V64 job becomes latest, do not show an old completed
# amount as if the new snapshot were complete; NULL must survive through CSV.
pending=str(uuid4())
sql(f"""INSERT INTO finance_profit_report_job(
 request_key,id,tenant_id,seller_marketplace_id,created_by,
 period_after,period_before,currency_code,state,total_orders,processed_orders,
 created_at)
 VALUES('{uuid4()}','{pending}','{tenant}','{mp}','{actor}',
 DATE '2026-11-02',DATE '2026-11-04','{site['currency']}',
 'READY',1,0,now()+interval '11 minutes')""")
current=call("/api/v1/business-overview")
pending_summary=next(x for x in current["marketplaces"] if x["id"]==mp)["latestReport"]
assert pending_summary["id"]==pending
assert pending_summary["state"]=="READY" and pending_summary["contribution"] is None
rows=list(csv.DictReader(io.StringIO(
    call("/api/v1/business-overview/export.csv",text_response=True))))
assert next(r for r in rows if r["marketplace_uuid"]==mp)[
    "v64_completed_contribution_only"]==""

# Create an actual scoped OPERATOR_READ session via V15 invitation flow.
# Full-store listings still work; the privileged business quality and V64
# metadata routes must remain Owner-only. No broad read-only role expansion.
invite=call("/api/v1/team/invitations",method="POST",body={
 "loginName":"v89-ci-source-quality-reader",
 "role":"OPERATOR_READ","sellerAccountId":seller_account})
csrf[id(staff)]=call("/api/v1/auth/csrf",client=staff)["token"]
call("/api/v1/auth/invitations/accept",client=staff,method="POST",
     body={"token":invite["token"],
           "password":"v89-isolated-reader-strong-secret-6789"},expected=204)
staff_info=login(staff,"v89-ci-source-quality-reader",
                  "v89-isolated-reader-strong-secret-6789")
assert not staff_info["isOwner"]
call(f"/api/v1/seller-marketplaces/{mp}/listings/page",client=staff)
call("/api/v1/business-overview",client=staff,expected=403)
call("/api/v1/business-overview/export.csv",client=staff,expected=403)
call(f"/api/v1/finance/marketplaces/{mp}/profit-reports",client=staff,expected=403)

assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{second}'""")=="0"
print("PASS V89: PG18 owner source overview all full tables, latest FBA only,"
      " per-site 2-currency no FX sum, V64 frozen reference & unfinished null,"
      " anti-formula quality CSV, 401/403 scoped staff and no Amazon writes")
