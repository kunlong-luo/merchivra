#!/usr/bin/env python3
"""V164 actual disposable PG18/HTTP expense-allocation conservation regression.

Only synthetic local Owner identities and *manual unverified* invoice metadata.
NO Amazon/Ads requests, accounting posting, bank read/write, real credentials.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import uuid4

assert os.getenv("GITHUB_ACTIONS") == "true"
assert os.getenv("MERCHIVRA_V164_DISPOSABLE_ONLY") == "true"
assert os.getenv("DB_HOST") == "127.0.0.1"
assert os.getenv("DB_NAME") == "merchivra"
assert os.getenv("MERCHIVRA_DEMO_ENABLED") == "true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED") == "false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES", "false") == "false"
BASE = "http://127.0.0.1:8080"

def sql(statement, should_pass=True):
    env = {**os.environ, "PGPASSWORD":os.environ["DB_PASSWORD"]}
    proc = subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
       "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",statement],
       env=env,capture_output=True,text=True,check=False,timeout=20)
    assert (proc.returncode == 0) == should_pass, "PG18 cost SQL invariant gate failed"
    return proc.stdout.strip()

def session():
    return {"client":build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar())),
            "csrf":None}

def call(sess,path,method="GET",payload=None,expected=200,form=False,csrf=True):
    data=None
    headers={"Accept":"application/json"}
    if payload is not None:
        data=(urlencode(payload) if form else json.dumps(payload)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if csrf and sess["csrf"] and method not in ("GET","HEAD"):
        headers["X-CSRF-TOKEN"]=sess["csrf"]
    try:
        with sess["client"].open(Request(BASE+path,method=method,
            headers=headers,data=data),timeout=20) as r:
            status,raw,cache=r.status,r.read(),r.headers.get("Cache-Control","")
    except HTTPError as e:
        status,raw,cache=e.code,e.read(),e.headers.get("Cache-Control","")
    assert status == expected,(method,path,status,expected)
    if status==200 and path.startswith("/api/v1/finance/marketplaces/"):
        assert "no-store" in cache.lower(),"Cost finance data must never be cached"
    try:return json.loads(raw)
    except (ValueError,TypeError):return None

def token(sess):
    sess["csrf"]=call(sess,"/api/v1/auth/csrf")["token"]
    assert sess["csrf"]

def login(sess,username,password):
    token(sess)
    call(sess,"/api/v1/auth/login","POST",
         {"username":username,"password":password},form=True)
    token(sess)
    return call(sess,"/api/v1/auth/me")

assert sql("SELECT count(*) FROM flyway_schema_history WHERE version='91' AND success")=="1"
assert sql("SELECT count(*) FROM flyway_schema_history WHERE version='92' AND success")=="1"
owner,other=session(),session()
anonymous=session()
call(anonymous,f"/api/v1/finance/marketplaces/{uuid4()}/cost-sources/{uuid4()}/allocations",expected=401)
me=login(owner,os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
         os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"])
assert me["isOwner"] and me["activeTenantId"]
tenant=me["activeTenantId"]
row=sql(f"""SELECT id || '|' || trim(currency_code)
  FROM seller_marketplace WHERE tenant_id='{tenant}' ORDER BY id LIMIT 1""")
assert row and "|" in row
market,currency=row.split("|")
root=f"/api/v1/finance/marketplaces/{market}/cost-sources"
before=[sql(f"SELECT count(*) FROM {t}") for t in (
    "finance_source_event","finance_profit_report_job",
    "ads_report_reviewed_cost","bank_payout_observation")]

# A second independent Owner is provisioned by the EXISTING one-use invitation
# API, not by bypassing Session/CSRF or fabricating role bindings.
reviewer_login="review164"+uuid4().hex[:12]
invitation=call(owner,"/api/v1/team/invitations","POST",
                {"loginName":reviewer_login,"role":"OWNER"})
assert len(invitation["token"])==43
token(other)
password="v164-disposable-independent-reviewer-only"
call(other,"/api/v1/auth/invitations/accept","POST",
     {"token":invitation["token"],"password":password},expected=204)
reviewer=login(other,reviewer_login,password)
assert reviewer["isOwner"] and reviewer["userId"]!=me["userId"]
assert reviewer["activeTenantId"]==tenant

source_key="V164-MANUAL-"+uuid4().hex[:12]
draft={"category":"INBOUND_FREIGHT","sourceKind":"MANUAL_INVOICE",
    "sourceKey":source_key,"documentSha256":uuid4().hex+uuid4().hex,
    "businessDate":"2026-11-07","amount":"10.000000","currency":currency}
src=call(owner,root,"POST",draft)
sid=src["id"]
assert src["state"]=="STAGED" and src["origin"]=="MANUAL_UNVERIFIED"
assert call(owner,root,"POST",draft)["id"]==sid
call(owner,root,"POST",{**draft,"amount":"11.00"},expected=409)
path=root+"/"+sid+"/allocations"
req=str(uuid4())
lines=[{"periodAfter":"2026-11-01","periodBefore":"2026-11-11",
        "amount":"3.250000"},
       {"periodAfter":"2026-11-11","periodBefore":"2026-11-21",
        "amount":"4.00"}]
proposal={"requestKey":req,"targetKind":"PERIOD",
          "currency":currency,"lines":lines}
# Cannot allocate a merely staged, unreviewed source.
call(owner,path,"POST",proposal,expected=409)
call(owner,root+"/"+sid+"/review","POST",{
    "accept":True,"reason":"Source maker cannot approve this own invoice"},expected=403)
review=call(other,root+"/"+sid+"/review","POST",{
    "accept":True,"reason":"Independently reviewed synthetic local invoice reference"})
assert review["state"]=="REVIEWED_UNVERIFIED"
# Fail at the new cost engine before ever writing a batch.
for bad in [
    {**proposal,"lines":[{"periodAfter":"2026-11-01",
        "periodBefore":"2026-11-11","amount":"10.000001"}]},
    {**proposal,"lines":[lines[0],lines[0]]},
    {**proposal,"lines":[lines[0],{"skuId":str(uuid4()),"amount":"1"}]},
    {**proposal,"currency":"ZZZ"},
    {**proposal,"lines":[{"periodAfter":"2026-11-01",
        "periodBefore":"2027-02-05","amount":"1"}]}
]:
    call(owner,path,"POST",bad,expected=400)
assert call(owner,path)==[]

# CSRF remains required even after local Owner session login.
call(owner,path,"POST",proposal,expected=403,csrf=False)
batch=call(owner,path,"POST",proposal)
bid=batch["id"]
assert batch["state"]=="PENDING_REVIEW"
assert batch["summary"]["allocatedAmount"]=="7.250000"
assert batch["summary"]["unallocatedAmount"]=="2.750000"
assert batch["summary"]["state"]=="PARTIAL_WITH_REMAINDER"
assert batch["summary"]["externalCostVerified"] is False
assert batch["recognizedProfitAllowed"] is False
# Stable canonicalization: swapped lines and equivalent decimal formatting
# replay the exact same proposal without inserting another batch.
assert call(owner,path,"POST",{**proposal,"lines":list(reversed(lines))})["id"]==bid
call(owner,path,"POST",{**proposal,"lines":[lines[0],{
     **lines[1],"amount":"4.10"}]},expected=409)
call(owner,path,"POST",{**proposal,"requestKey":str(uuid4())},expected=409)
assert len(call(owner,path))==1
decision=path+"/"+bid+"/decision"
call(owner,decision,"POST",{"accept":True,
    "reason":"Proposer cannot approve own distribution"},expected=403)
approved=call(other,decision,"POST",{"accept":True,
    "reason":"Independent review confirms synthetic invoice amount conservation"})
assert approved["state"]=="APPROVED_UNVERIFIED"
assert approved["summary"]["unallocatedAmount"]=="2.750000"
assert not approved["recognizedProfitAllowed"]
call(other,decision,"POST",{"accept":True,
    "reason":"Duplicate approval must not change final review"},expected=409)
assert [x["action"] for x in call(owner,path+"/"+bid+"/audit")]==[
    "APPROVED_UNVERIFIED","PROPOSED"]
call(owner,f"/api/v1/finance/marketplaces/{uuid4()}/cost-sources/{sid}/allocations",
    expected=404)
call(other,root+"/"+str(uuid4())+"/allocations",expected=404)

# Direct SQL must also fail closed: neither manual approval source facts nor
# allocation amounts / verified review nor audit history may be silently edited.
sql(f"UPDATE finance_cost_allocation_line_v92 SET expense_amount=8 "
    f"WHERE tenant_id='{tenant}' AND batch_id='{bid}'",False)
sql(f"DELETE FROM finance_cost_allocation_line_v92 "
    f"WHERE tenant_id='{tenant}' AND batch_id='{bid}'",False)
sql(f"UPDATE finance_cost_allocation_batch_v92 SET state='REJECTED' "
    f"WHERE tenant_id='{tenant}' AND id='{bid}'",False)
sql(f"DELETE FROM finance_cost_allocation_audit_v92 "
    f"WHERE tenant_id='{tenant}' AND batch_id='{bid}'",False)
sql(f"DELETE FROM finance_cost_source_audit_v91 "
    f"WHERE tenant_id='{tenant}' AND source_id='{sid}'",False)
assert sql(f"""SELECT COALESCE(sum(expense_amount),0) FROM
 finance_cost_allocation_line_v92 WHERE tenant_id='{tenant}' AND batch_id='{bid}'""")=="7.250000"
after=[sql(f"SELECT count(*) FROM {t}") for t in (
    "finance_source_event","finance_profit_report_job",
    "ads_report_reviewed_cost","bank_payout_observation")]
assert before==after,"Local unverified costs must not post to real finance/Ads/bank"
print("PASS V164: real PG18 V91/V92 + Owner/CSRF maker-checker, immutable "
      "manual cost, exact 7.25/2.75 allocation, idempotency, 400/403/404/409, "
      "SQL immutable source/lines/audit, and zero GL/Ads/Finances/bank postings")
