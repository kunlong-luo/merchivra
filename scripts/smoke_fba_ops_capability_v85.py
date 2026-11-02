#!/usr/bin/env python3
"""V85: isolated real PostgreSQL18 HTTP cross-module capability regression.
Run only after synthetic V76..V84 tests. Amazon SP-API/OAuth is disabled.
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
def sql(q):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    p=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
      "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",q],
      capture_output=True,text=True,env=env,check=False)
    assert p.returncode==0,p.stderr[-1000:]
    return p.stdout.strip()
def browser():
    return build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
admin=browser()
limited=browser()
csrf={}
def call(path,method="GET",body=None,expected=200,client=admin,form=False):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method=="POST" and csrf.get(id(client)):
        headers["X-CSRF-TOKEN"]=csrf[id(client)]
    try:
        with client.open(Request("http://127.0.0.1:8080"+path,method=method,
                data=data,headers=headers),timeout=35) as resp:
            status,payload=resp.status,resp.read()
    except HTTPError as exc:
        status,payload=exc.code,exc.read()
    assert status==expected,(method,path,status,expected,payload[:900])
    try:return json.loads(payload)
    except (ValueError,TypeError):return payload
def login(client,username,password):
    csrf[id(client)]=call("/api/v1/auth/csrf",client=client)["token"]
    call("/api/v1/auth/login","POST",{"username":username,"password":password},
         client=client,form=True)
    csrf[id(client)]=call("/api/v1/auth/csrf",client=client)["token"]
    return call("/api/v1/auth/me",client=client)

call("/api/v1/operations/summary",expected=401,client=limited)
owner=login(admin,os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
            os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"])
staff=login(limited,"v76-ci-independent-owner",
      "v76-ci-independent-owner-disposable-password")
tenant=owner["activeTenantId"]
assert tenant==staff["activeTenantId"] and staff["userId"]!=owner["userId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp
fba=sql(f"""SELECT id FROM ops_finding WHERE tenant_id='{tenant}'
 AND seller_marketplace_id='{mp}'
 AND rule_code IN ('FBA_PO_EVIDENCE_CHANGED','FBA_PO_EXCEPTION_OVERDUE')
 ORDER BY id LIMIT 1""")
assert fba,"V78 must generate actual local FBA purchase Ops finding"
ops="/api/v1/operations"
rules=f"{ops}/marketplaces/{mp}/rules"
work=f"/api/v1/fba/marketplaces/{mp}/purchase-reconciliation/workbench"
recon=f"/api/v1/fba/marketplaces/{mp}/purchase-reconciliation"
permission=f"{recon}/permissions"
insights=f"/api/v1/exception-insights/marketplaces/{mp}"
current=call(permission+"/me",client=limited)
assert current["grants"]["view"] and current["grants"]["review"]
assert not current["grants"]["assign"]
assert not current["grants"]["resolve"]
assert not current["grants"]["configure"]
assert not current["grants"]["manage"]
assert call(recon,client=limited)["amazonVerified"] is False
initial=call(ops+"/findings?filter=ALL",client=limited)
assert any(x["id"]==fba for x in initial),initial
assert any(x["ruleCode"].startswith("FBA_PO_") for x in initial)
call(f"{ops}/findings/{fba}/attention-audit",client=limited)
before_scan=sql(f"SELECT count(*) FROM ops_automation_run WHERE tenant_id='{tenant}'")
call(ops+"/scan","POST",expected=403,client=limited)
assert sql(f"SELECT count(*) FROM ops_automation_run WHERE tenant_id='{tenant}'")==before_scan
call(f"{ops}/findings/{fba}/claim","POST",expected=403,client=limited)
call(f"{ops}/findings/{fba}/release","POST",expected=403,client=limited)
call(f"{ops}/findings/{fba}/state","POST",{"state":"DONE"},
     expected=403,client=limited)
codes={x["ruleCode"]:x for x in call(rules,client=limited)}
assert len(codes)==8 and "FBA_PO_EXCEPTION_OVERDUE" in codes
code="FBA_PO_EXCEPTION_OVERDUE"
call(f"{rules}/{code}","POST",{
 "enabled":codes[code]["enabled"],
 "lowStockThreshold":None,
 "expectedVersion":codes[code]["version"]
},expected=403,client=limited)
# The restriction must NOT overreach unrelated Ops stock rules; a valid local
# low-stock policy change remains governed by its original Owner boundary.
stock=codes["LOW_STOCK"]
changed=call(rules+"/LOW_STOCK","POST",{
 "enabled":stock["enabled"],
 "lowStockThreshold":stock["lowStockThreshold"],
 "expectedVersion":stock["version"]
},client=limited)
assert changed["version"]==stock["version"]+1

# Explicit revoke VIEW while REVIEW remains granted: guessing an ID must not
# let this actor read original evidence or perform blind human actions.
grants={**current["grants"],"view":False}
saved=call(permission+"/owners/"+staff["userId"],"POST",{
 "grants":grants,"expectedVersion":current["version"],
 "reason":"V85 remove evidence visibility, retain review flag to test deny"
})
assert saved["version"]==current["version"]+1
assert saved["grants"]["review"] is True and not saved["grants"]["view"]
for path in (recon,work,insights,insights+"/history"):
    call(path,expected=403,client=limited)
call(f"{ops}/findings/{fba}/attention-audit",expected=403,client=limited)
call(f"{ops}/findings/{fba}/state","POST",{"state":"OPEN"},
     expected=403,client=limited)
call(f"{ops}/findings/{fba}/snooze","POST",{
 "minutes":15,"note":"V85 prevent blind review of revoked FBA evidence"
},expected=403,client=limited)
case=sql(f"""SELECT inbound_line_id FROM fba_purchase_exception_case
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}' LIMIT 1""")
assert case
call(work+f"/cases/{case}/actions","POST",{
 "action":"REOPEN","expectedVersion":0,
 "expectedFingerprint":"a"*64,
 "note":"V85 cannot reopen source without VIEW permission"
},expected=403,client=limited)
for name in ("ALL","ACTIVE","ACTIONABLE"):
    filtered=call(ops+"/findings?filter="+name,client=limited)
    assert all(not (x["marketplaceId"]==mp and
                   x["ruleCode"] in ("FBA_PO_EVIDENCE_CHANGED",
                                    "FBA_PO_EXCEPTION_OVERDUE")) for x in filtered),filtered
# Recompute the summary from the same persisted full-table visibility rule
# rather than truncating or assuming hidden FBA findings count as zero.
expected_summary=sql(f"""SELECT
 count(*) FILTER (WHERE state='OPEN'),
 count(*) FILTER (WHERE state='ACKNOWLEDGED'),
 count(*) FILTER (WHERE state='DONE'),
 count(*) FILTER (WHERE state='CLEARED'),
 count(*) FILTER (WHERE state IN ('OPEN','ACKNOWLEDGED')
                         AND snoozed_until>now())
 FROM ops_finding
 WHERE tenant_id='{tenant}'
 AND NOT (seller_marketplace_id='{mp}' AND
 rule_code IN ('FBA_PO_EVIDENCE_CHANGED','FBA_PO_EXCEPTION_OVERDUE'))""").split("|")
summary=call(ops+"/summary",client=limited)
assert list(summary.values())==[int(v) for v in expected_summary],(summary,expected_summary)
visible_rules=call(rules,client=limited)
assert "LOW_STOCK" in {x["ruleCode"] for x in visible_rules}
assert not any(x["ruleCode"].startswith("FBA_PO_") for x in visible_rules)
assert not any(x["ruleCode"].startswith("FBA_PO_")
               for x in call(rules+"/audit",client=limited))
# Even after revocation, non-FBA Ops configuration remains independent.
assert call(permission+"/me",expected=403,client=limited) is not None
call(ops+"/scan","POST",expected=403,client=limited)
call(f"{rules}/{code}","POST",{
 "enabled":codes[code]["enabled"],
 "expectedVersion":codes[code]["version"]
},expected=403,client=limited)
assert sql(f"SELECT count(*) FROM ops_automation_run WHERE tenant_id='{tenant}'")==before_scan
assert sql(f"""SELECT count(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'""")=="0"
print("PASS V85: PG18 same-session cross-module V84 VIEW/REVIEW/ASSIGN/RESOLVE/CONFIGURE constraints, FBA-only filtered Ops summary and rules, unrelated Ops settings intact, no scan or Amazon inventory writes")
