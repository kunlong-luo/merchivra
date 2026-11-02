#!/usr/bin/env python3
"""V80 real PG18/Spring Boot HTTP regression, after V76-V79 fictional fixtures.
Read-only evidence triage: no live OAuth/SP-API, case writes or dispatch.
"""
import http.cookiejar
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
host="http://127.0.0.1:8080"
browser=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=""
def sql(query):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    result=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
      "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",query],
      capture_output=True,text=True,env=env,check=False)
    assert result.returncode==0,result.stderr[-700:]
    return result.stdout.strip()
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method=="POST" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with browser.open(Request(host+path,headers=headers,data=data,method=method),
                          timeout=30) as response:
            status,payload=response.status,response.read()
    except HTTPError as err:
        status,payload=err.code,err.read()
    assert status==expected,(method,path,status,expected,payload[:500])
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
foreign=str(uuid4())
call("/api/v1/exception-insights/marketplaces/"+foreign,expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
tenant=call("/api/v1/auth/me")["activeTenantId"]
marketplace=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert marketplace, "Run V76-V79 mock fixtures first"
route="/api/v1/exception-insights/marketplaces/"+marketplace
call("/api/v1/exception-insights/marketplaces/"+foreign,expected=404)
run_count=sql(f"SELECT count(*) FROM ops_automation_run WHERE tenant_id='{tenant}'")
audit_count=sql(f"SELECT count(*) FROM fba_purchase_exception_audit WHERE tenant_id='{tenant}'")
cases_before=sql(f"""SELECT count(*) FROM fba_purchase_exception_case
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{marketplace}'""")
report=call(route)
assert report["marketplaceId"]==marketplace
assert report["amazonVerified"] is False and report["fbaStockCredited"] is False
assert not report["sourceTruncated"] and report["evidenceLines"]>=5
assert report["cases"]["total"]==int(cases_before) and report["cases"]["total"]>=3
assert report["cases"]["resolved"]==0 or report["cases"]["total"]>=report["cases"]["resolved"]
assert report["workload"]["totalCases"]==report["cases"]["total"]
assert report["actionableCount"]>=2
assert len(report["recommendations"])<=30 and not report["recommendationsTruncated"]
assert all(x["priority"] in ("P0","P1","P2","P3") for x in report["recommendations"])
assert report["recommendations"]==sorted(report["recommendations"],
    key=lambda x:(x["priority"],x["dueAt"] or "9999",x["lineId"]))
priorities={x["shipmentReference"]:x for x in report["recommendations"]}
overdue=priorities["V76-UNLINKED-DRAFT"]
assert overdue["priority"]=="P0" and overdue["reasonCode"]=="PAST_DUE",overdue
pending=priorities["V76-IDENTITY-PENDING"]
assert pending["reasonCode"]=="INDEPENDENT_IDENTITY_REVIEW",pending
assert pending["priority"]=="P1"
owner_ids={x["userId"] for x in report["workload"]["owners"]}
assert len(owner_ids)>=2
for recommendation in report["recommendations"]:
    if recommendation["recommendedOwnerId"]:
        assert recommendation["assigneeId"] is None
        assert recommendation["recommendedOwnerId"] in owner_ids
assert any(x["ruleCode"]=="FBA_PO_EVIDENCE_CHANGED" for x in report["operations"])
# Evidence drift on already-assigned (OPEN) case outranks ordinary warnings.
confirmed=priorities["V76-IDENTITY-CONFIRMED"]
asn=sql(f"""SELECT asn_id FROM fba_inbound_purchase_link
 WHERE tenant_id='{tenant}' AND shipment_id='{confirmed['shipmentId']}'
 AND state='CONFIRMED'""")
assert asn
sql(f"""UPDATE erp_purchase_asn SET status='SHIPPED'
 WHERE tenant_id='{tenant}' AND id='{asn}'""")
changed=call(route)
by_line={x["lineId"]:x for x in changed["recommendations"]}
drift=by_line[confirmed["lineId"]]
assert drift["priority"]=="P0" and drift["reasonCode"]=="SOURCE_CHANGED",drift
assert drift["state"]=="OPEN"
# Repeated GET must not trigger Ops scans, assignments, audits or inventory writes.
assert sql(f"SELECT count(*) FROM ops_automation_run WHERE tenant_id='{tenant}'")==run_count
assert sql(f"SELECT count(*) FROM fba_purchase_exception_audit WHERE tenant_id='{tenant}'")==audit_count
assert sql(f"""SELECT count(*) FROM fba_purchase_exception_case
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{marketplace}'""")==cases_before
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{marketplace}'""")=="0"
print("PASS V80: PG18 HTTP tenant boundary, existing Ops categories and case totals, priority precedence, owner suggestions, read-only zero Amazon/inventory writes")
