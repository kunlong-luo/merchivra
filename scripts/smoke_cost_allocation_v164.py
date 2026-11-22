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
# V185: an existing unreviewed V92 PERIOD distribution can be shown as a
# bounded original-currency daily projection, never booked to net profit.
preview_path=path+"/"+bid+"/periodization-preview"
call(anonymous,preview_path,expected=401)
pending_preview=call(owner,preview_path)
assert pending_preview["ruleVersion"]=="ILLUSTRATIVE_DAILY_STRAIGHT_LINE_MICRO_UNITS_V1"
assert pending_preview["allocationState"]=="PENDING_REVIEW"
assert pending_preview["sourceAmount"]=="10.000000"
assert pending_preview["projectedAmount"]=="7.250000"
assert pending_preview["unallocatedAmount"]=="2.750000"
assert [(m["period"],m["projectedAmount"]) for m in pending_preview["months"]]==[
    ("2026-11","7.250000")]
assert "INDEPENDENT_V92_ALLOCATION_APPROVAL_NOT_COMPLETE" in pending_preview["blockerCodes"]
assert not any(pending_preview[k] for k in (
    "externalSourceVerified","accountingPolicyApproved","currencyConverted",
    "refundCogsReversalVerified","ledgerPosted","netProfitEligible"))
assert pending_preview["realizedNetProfit"] is None
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
approved_preview=call(owner,preview_path)
assert approved_preview["allocationState"]=="APPROVED_UNVERIFIED"
assert approved_preview["months"]==pending_preview["months"]
assert approved_preview["projectedAmount"]==pending_preview["projectedAmount"]
assert "G1_DAILY_PERIODIZATION_POLICY_NOT_APPROVED" in approved_preview["blockerCodes"]
assert "INDEPENDENT_V92_ALLOCATION_APPROVAL_NOT_COMPLETE" not in approved_preview["blockerCodes"]
assert not approved_preview["netProfitEligible"] and not approved_preview["ledgerPosted"]
call(other,decision,"POST",{"accept":True,
    "reason":"Duplicate approval must not change final review"},expected=409)
assert [x["action"] for x in call(owner,path+"/"+bid+"/audit")]==[
    "APPROVED_UNVERIFIED","PROPOSED"]
call(owner,f"/api/v1/finance/marketplaces/{uuid4()}/cost-sources/{sid}/allocations",
    expected=404)
call(other,root+"/"+str(uuid4())+"/allocations",expected=404)
call(owner,f"/api/v1/finance/marketplaces/{uuid4()}/cost-sources/{sid}"
    f"/allocations/{bid}/periodization-preview",expected=404)
call(owner,path+"/"+str(uuid4())+"/periodization-preview",expected=404)

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
# V185: independently reviewed synthetic 4-day, cross-month manual cost
# schedule. Exact six-decimal conservation survives Nov/Dec boundary and
# cannot be interpreted as actual GAAP accrual, COGS reversal or bank FX.
second=call(owner,root,"POST",{
    "category":"FBA_STORAGE","sourceKind":"MANUAL_INVOICE",
    "sourceKey":"V185-PERIOD-"+uuid4().hex[:14],
    "documentSha256":uuid4().hex+uuid4().hex,
    "businessDate":"2026-11-15","amount":"12.000001","currency":currency})
second_id=second["id"]
call(other,root+"/"+second_id+"/review","POST",{
    "accept":True,
    "reason":"Independent reviewer checks synthetic local four-day service period"})
p2=root+"/"+second_id+"/allocations"
proposal2=call(owner,p2,"POST",{
    "requestKey":str(uuid4()),"targetKind":"PERIOD","currency":currency,
    "lines":[{"periodAfter":"2026-11-29","periodBefore":"2026-12-03",
              "amount":"10.000001"}]})
preview2=p2+"/"+proposal2["id"]+"/periodization-preview"
stage=call(owner,preview2)
assert stage["allocationState"]=="PENDING_REVIEW"
assert stage["allocatedAmount"]=="10.000001"
assert stage["unallocatedAmount"]=="2.000000"
assert stage["projectedAmount"]=="10.000001"
assert [(m["period"],m["projectedAmount"]) for m in stage["months"]]==[
    ("2026-11","5.000001"),("2026-12","5.000000")]
assert "ORIGINAL_COST_REMAINDER_UNALLOCATED" in stage["blockerCodes"]
assert not stage["overlappingServiceDays"]
call(other,p2+"/"+proposal2["id"]+"/decision","POST",{
    "accept":True,
    "reason":"Approve synthetic allocation math only, not actual accrual policy"})
after_review=call(owner,preview2)
assert after_review["allocationState"]=="APPROVED_UNVERIFIED"
assert after_review["months"]==stage["months"]
assert not after_review["externalSourceVerified"]
assert not after_review["accountingPolicyApproved"]
assert not after_review["netProfitEligible"]
assert after_review["realizedNetProfit"] is None
# A plan ID belonging to another V91 source cannot be read through this path.
call(owner,path+"/"+proposal2["id"]+"/periodization-preview",expected=404)

# V186: global single-source cost bridge. All SQL rows are synthetic
# original-currency manual claims; even APPROVED_UNVERIFIED is not a GL debit.
# A staged source without an active plan must remain UNASSIGNED, not zero.
unowned=call(owner,root,"POST",{
    "category":"OTHER_OPERATING","sourceKind":"MANUAL_INVOICE",
    "sourceKey":"V186-UNASSIGNED-"+uuid4().hex[:14],
    "documentSha256":uuid4().hex+uuid4().hex,
    "businessDate":"2026-11-19","amount":"4.000000","currency":currency})
assert unowned["id"]!=sid and unowned["id"]!=second_id
bridge_url=f"/api/v1/finance/marketplaces/{market}/cost-ownership-bridge"
period_q="?periodStart=2026-11-01&periodEndExclusive=2026-12-01"
call(anonymous,bridge_url+period_q,expected=401)
call(owner,bridge_url+"?periodStart=2026-11-01&periodEndExclusive=2026-11-01",
     expected=400)
call(owner,bridge_url+"?periodStart=2025-01-01&periodEndExclusive=2026-12-01",
     expected=400)
call(owner,f"/api/v1/finance/marketplaces/{uuid4()}/cost-ownership-bridge"
     +period_q,expected=404)
bridge=call(owner,bridge_url+period_q)
assert bridge["sourceDateBasis"]=="V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE"
assert bridge["sourceCount"]==int(sql(f"""
 SELECT count(*) FROM finance_cost_source_v91
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{market}'
   AND business_date>='2026-11-01' AND business_date<'2026-12-01'"""))
assert bridge["sourceCount"]==sum(x["sourceCount"] for x in bridge["buckets"])
assert not bridge["doubleCountingAcrossDimensionsAllowed"]
assert bridge["realizedNetProfit"] is None
for key in ("externalChargeVerified","refundCogsReversalVerified",
            "fxConversionApproved","bankReconciled",
            "ledgerPosted","netProfitEligible"):
    assert bridge[key] is False,key
for b in bridge["buckets"]:
    assert not b["derivativeSkuOrderPeriodProfitDeductionsAllowed"]
    assert not b["externalChargeVerified"] and not b["netProfitEligible"]
    assert b["sourceCount"]==(b["approvedUnverifiedSourceCount"]+
        b["pendingSourceCount"]+b["unassignedSourceCount"])
    assert (b["approvedUnverifiedAllocatedAmount"] is None)==(
        b["approvedUnverifiedSourceCount"]==0)
    assert (b["approvedUnverifiedRemainderAmount"] is None)==(
        b["approvedUnverifiedSourceCount"]==0)
assert any(x["primaryDimension"]=="UNASSIGNED"
           and x["category"]=="OTHER_OPERATING"
           and x["unassignedSourceCount"]>=1 for x in bridge["buckets"])
assert any(x["primaryDimension"]=="PERIOD"
           and x["category"]=="INBOUND_FREIGHT"
           and x["approvedUnverifiedSourceCount"]>=1 for x in bridge["buckets"])
assert any(x["primaryDimension"]=="PERIOD"
           and x["category"]=="FBA_STORAGE"
           and x["approvedUnverifiedSourceCount"]>=1 for x in bridge["buckets"])
assert "REFUND_COGS_REVERSAL_NEEDS_PHYSICAL_RECEIPT_AND_FINANCIAL_POLICY" in bridge["blockerCodes"]
assert "SOURCE_WITHOUT_ACTIVE_V92_OWNER_DIMENSION" in bridge["blockerCodes"]
# Independent source-once reconciliation. This is not checking equality
# against the same V186 query: it uses raw V91 amounts grouped only by
# original currency, never the many-to-one V92 allocation-line table.
from decimal import Decimal
real={}
for entry in sql(f"""
 SELECT btrim(currency_code)||'|'||
        sum(amount)::numeric(30,6)::text
 FROM finance_cost_source_v91
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{market}'
   AND business_date>='2026-11-01' AND business_date<'2026-12-01'
 GROUP BY currency_code""").splitlines():
    cc,amount=entry.split("|")
    real[cc]=Decimal(amount)
from_bridge={}
for b in bridge["buckets"]:
    from_bridge[b["currency"]]=from_bridge.get(b["currency"],Decimal(0))+Decimal(
        b["sourceClaimedAmount"])
assert from_bridge==real,"V186 dimension grouping must not multiply V91 source charges"
empty_bridge=call(owner,bridge_url+
    "?periodStart=2026-12-01&periodEndExclusive=2027-01-01")
assert empty_bridge["sourceCount"]==int(sql(f"""
 SELECT count(*) FROM finance_cost_source_v91
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{market}'
   AND business_date>='2026-12-01' AND business_date<'2027-01-01'"""))
assert empty_bridge["realizedNetProfit"] is None
assert not empty_bridge["netProfitEligible"]

after=[sql(f"SELECT count(*) FROM {t}") for t in (
    "finance_source_event","finance_profit_report_job",
    "ads_report_reviewed_cost","bank_payout_observation")]
assert before==after,"Local unverified costs must not post to real finance/Ads/bank"
# V188: construct a disposable V64 FROZEN report through the existing Owner
# API. The only new write is V64's own intentionally persisted synthetic
# candidate cohort; the new cost-waterfall endpoint remains GET-only and may
# NEVER turn V91/V92 claims into V162/V64 realized profit or bank evidence.
# The period ends before today's date so V64 doesn't accept future cohorts.
report_root=f"/api/v1/finance/marketplaces/{market}/profit-reports"
report_job=call(owner,report_root,"POST",{
    "requestKey":str(uuid4()),"after":"2026-11-01",
    "before":"2026-11-20"})
job_id=report_job["id"]
waterfall_url=f"/api/v1/finance/marketplaces/{market}/cost-waterfall-preview/"+job_id
call(anonymous,waterfall_url,expected=401)
call(owner,f"/api/v1/finance/marketplaces/{market}/cost-waterfall-preview/"
     +str(uuid4()),expected=404)
call(owner,f"/api/v1/finance/marketplaces/{uuid4()}/cost-waterfall-preview/"
     +job_id,expected=404)
prior_cost_event_rows=sql(f"""SELECT count(*) FROM finance_source_event
 WHERE tenant_id='{tenant}'""")
prior_bank_rows=sql("SELECT count(*) FROM bank_payout_observation")
# V194: this new V64 cohort has not been fully captured with independently
# validated V1 order/SKU evidence. Its V188 waterfall MUST fail closed; an
# unaudited 200 and null-looking money is no longer a valid test expectation.
capture_url=f"/api/v1/finance/marketplaces/{market}/capture-verification/"+job_id
call(anonymous,capture_url,expected=401)
call(owner,f"/api/v1/finance/marketplaces/{uuid4()}/capture-verification/"+job_id,
     expected=404)
call(owner,f"/api/v1/finance/marketplaces/{market}/capture-verification/"
     +str(uuid4()),expected=404)
capture=call(owner,capture_url)
assert capture["profitBaselineAllowed"] is False
assert capture["fingerprintMismatchOrders"]==0,(
    "V194: newly created, not-yet-captured V64 cohort must not be corrupt")
assert capture["historicalLegacyOrders"]==0,(
    "V194: new cohort must not silently inherit unversioned old evidence")
assert "V194_PROFIT_ARITHMETIC_BLOCKED" in capture["blockerCodes"]
assert "EXTERNAL_FINANCES_ADS_BANK_AND_REFUND_COGS_UNVERIFIED" in capture["blockerCodes"]
assert call(other,capture_url)==capture  # independent Owner, same tenant
call(owner,waterfall_url,expected=409)
call(other,waterfall_url,expected=409)
assert sql(f"""SELECT count(*) FROM finance_source_event
 WHERE tenant_id='{tenant}'""")==prior_cost_event_rows
assert sql("SELECT count(*) FROM bank_payout_observation")==prior_bank_rows
assert call(owner,capture_url)==capture  # stable, read-only evidence
call(owner,waterfall_url,expected=409)  # stable denial; never synthetic net profit
print("PASS V164: real PG18 V91/V92 + Owner/CSRF maker-checker, immutable "
      "manual cost, exact 7.25/2.75 allocation, idempotency, 400/403/404/409, "
      "SQL immutable source/lines/audit, and zero GL/Ads/Finances/bank postings")
