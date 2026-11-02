#!/usr/bin/env python3
"""V81 PG18 real HTTP verification after V77-V80 isolated mock fixtures.
Never run this script against a real Seller or non-disposable database.
"""
import http.cookiejar
import json
import os
import subprocess
from datetime import datetime,timedelta,timezone
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor,Request,build_opener
from uuid import uuid4

assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=""
def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    result=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
       "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",statement],
      capture_output=True,text=True,env=env,check=False)
    assert result.returncode==0,result.stderr[-900:]
    return result.stdout.strip()
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method=="POST" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,method=method,
             headers=headers,data=data),timeout=25) as res:
            status,payload=res.status,res.read()
    except HTTPError as err:
        status,payload=err.code,err.read()
    assert status==expected,(method,path,status,expected,payload[:700])
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
foreign=str(uuid4())
root="/api/v1/exception-insights/marketplaces/"
call(root+foreign+"/history",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
    "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
    "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
tenant=call("/api/v1/auth/me")["activeTenantId"]
market=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert market,"Run V76-V80 synthetic fixtures first"
call(root+foreign+"/history",expected=404)
url=root+market+"/history"
before_audit=sql(f"""SELECT count(*) FROM fba_purchase_exception_audit WHERE tenant_id='{tenant}'""")
before_case=sql(f"""SELECT count(*) FROM fba_purchase_exception_case WHERE tenant_id='{tenant}'""")
before_ops=sql(f"""SELECT count(*) FROM ops_automation_run WHERE tenant_id='{tenant}'""")
report=call(url)
assert report["marketplaceId"]==market
assert report["origin"]=="LOCAL_AUDIT" and report["timezone"]=="UTC"
assert not report["amazonVerified"] and not report["fbaStockCredited"]
assert report["dayCount"]==30 and len(report["daily"])==30
today=datetime.now(timezone.utc).date()
days=[(today-timedelta(days=29-i)).isoformat() for i in range(30)]
assert [x["day"] for x in report["daily"]]==days
assert report["fromDay"]==days[0] and report["throughDay"]==days[-1]
summary=report["summary"]
assert summary["registered"]>=3 and summary["auditedActions"]>=10,summary
assert summary["resolvedActions"]>=1 and summary["reopenActions"]>=1,summary
assert summary["affectedByReopen"]>=1,summary
assert summary["evidenceRefreshActions"]>=1,summary
assert sum(x["registered"] for x in report["daily"])==summary["registered"]
assert sum(x["resolvedActions"] for x in report["daily"])==summary["resolvedActions"]
assert sum(x["reopenActions"] for x in report["daily"])==summary["reopenActions"]
assert sum(x["evidenceRefreshActions"] for x in report["daily"])==summary["evidenceRefreshActions"]
assert not report["actorsTruncated"] and report["actors"]
assert not report["recurrencesTruncated"] and report["recurrences"]
assert sum(x["resolvedActions"] for x in report["actors"])==summary["resolvedActions"]
assert sum(x["reopenedActions"] for x in report["actors"])==summary["reopenActions"]
assert any(x["meanFirstResolutionHours"] is not None for x in report["actors"])
assert all(x["meanFirstResolutionHours"] is None or
           x["meanFirstResolutionHours"]>=0 for x in report["actors"])
case=next(x for x in report["recurrences"] if x["shipmentReference"]=="V76-IDENTITY-CONFIRMED")
assert case["reopenActions"]>=1 and case["evidenceRefreshActions"]>=1,case
assert case["lastReopenedAt"] is not None
assert case["state"]=="OPEN",case
assert summary["affectedByReopen"]==int(sql(f"""SELECT count(DISTINCT a.case_id)
 FROM fba_purchase_exception_audit a
 JOIN fba_purchase_exception_case c ON c.tenant_id=a.tenant_id AND c.id=a.case_id
 WHERE c.tenant_id='{tenant}' AND c.seller_marketplace_id='{market}'
 AND a.action='REOPEN' AND a.occurred_at >= now()-INTERVAL '29 days'"""))
again=call(url)
assert again==report
assert sql(f"SELECT count(*) FROM fba_purchase_exception_audit WHERE tenant_id='{tenant}'")==before_audit
assert sql(f"SELECT count(*) FROM fba_purchase_exception_case WHERE tenant_id='{tenant}'")==before_case
assert sql(f"SELECT count(*) FROM ops_automation_run WHERE tenant_id='{tenant}'")==before_ops
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{market}'""")=="0"
print("PASS V81: PG18 30-day UTC zero-filled audit events, actor first-resolve, repeat cases, owner-session/404, read-only no scan or FBA inventory writes")
