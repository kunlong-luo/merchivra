#!/usr/bin/env python3
"""V165 disposable PG18/HTTP identity-overlap hold, never a profit certificate."""
import http.cookiejar
import json
import os
import subprocess
from urllib.parse import urlencode
from urllib.error import HTTPError
from urllib.request import Request, HTTPCookieProcessor, build_opener
from uuid import uuid4

assert os.getenv("GITHUB_ACTIONS")=="true"
assert os.getenv("MERCHIVRA_V165_DISPOSABLE_ONLY")=="true"
assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_ALLOW_WRITES","false")=="false"
BASE="http://127.0.0.1:8080"
def sql(q):
    p=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",q],
        capture_output=True,text=True,timeout=20,
        env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]})
    assert p.returncode==0,"V165 disposable PG18 fixture error"
    return p.stdout.strip()
def sess():
    return {"agent":build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar())),
            "csrf":None}
def call(c,path,method="GET",data=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    if c["csrf"] and method=="POST":headers["X-CSRF-TOKEN"]=c["csrf"]
    body=None
    if data is not None:
        body=(urlencode(data) if form else json.dumps(data)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    try:
        with c["agent"].open(Request(BASE+path,method=method,data=body,
              headers=headers),timeout=20) as r:
            status,raw,cache=r.status,r.read(),r.headers.get("Cache-Control","")
    except HTTPError as e:
        status,raw,cache=e.code,e.read(),e.headers.get("Cache-Control","")
    assert status==expected,(path,method,status,expected)
    if status==200 and "/cost-sources/" in path:
        assert "no-store" in cache.lower()
    try:return json.loads(raw)
    except (ValueError,TypeError):return None
def csrf(c):
    c["csrf"]=call(c,"/api/v1/auth/csrf")["token"]
def login(c,user,passw):
    csrf(c)
    call(c,"/api/v1/auth/login","POST",
         {"username":user,"password":passw},form=True)
    csrf(c)
    return call(c,"/api/v1/auth/me")

owner,checker=sess(),sess()
call(owner,f"/api/v1/finance/marketplaces/{uuid4()}/cost-sources/{uuid4()}/identity-check",
     expected=401)
me=login(owner,os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
         os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"])
tenant=me["activeTenantId"]
mp,currency=sql(f"""SELECT id||'|'||trim(currency_code)
 FROM seller_marketplace WHERE tenant_id='{tenant}' ORDER BY id LIMIT 1""").split("|")
user=me["userId"]
reviewer_login="identity165"+uuid4().hex[:12]
inv=call(owner,"/api/v1/team/invitations","POST",
         {"loginName":reviewer_login,"role":"OWNER"})
csrf(checker)
password="v165-disposable-local-reviewer-only"
call(checker,"/api/v1/auth/invitations/accept","POST",
     {"token":inv["token"],"password":password},expected=204)
peer=login(checker,reviewer_login,password)
assert peer["isOwner"] and peer["activeTenantId"]==tenant
root=f"/api/v1/finance/marketplaces/{mp}/cost-sources"
before=[sql("SELECT count(*) FROM "+t) for t in
    ("finance_profit_report_job","bank_payout_observation","ads_report_reviewed_cost")]
def create(name,doc,category):
    return call(owner,root,"POST",{"category":category,
        "sourceKind":"MANUAL_INVOICE","sourceKey":name,
        "documentSha256":doc,"businessDate":"2026-11-09",
        "currency":currency,"amount":"10.000000"})
def signals(p):
    return {x["code"]:x["exactLocalIdentityOverlap"] for x in p["signals"]}
key_a="V165-ADS-"+uuid4().hex[:16]
key_b="V165-FREIGHT-"+uuid4().hex[:12]
sha_a=uuid4().hex+uuid4().hex
sha_b=uuid4().hex+uuid4().hex
a=create(key_a,sha_a,"ADS_SPEND")
b=create(key_b,sha_b,"INBOUND_FREIGHT")
url_a=root+"/"+a["id"]
url_b=root+"/"+b["id"]
p_a=call(owner,url_a+"/identity-check")
p_b=call(owner,url_b+"/identity-check")
assert p_a["status"]=="NO_LOCAL_IDENTITY_MATCH_UNVERIFIED"
assert p_b["status"]=="NO_LOCAL_IDENTITY_MATCH_UNVERIFIED"
for p in (p_a,p_b):
    assert not p["approvalHeld"] and not p["completeSourceCoverage"]
    assert not p["independentExternalEvidenceVerified"]
    assert not p["netProfitVerified"] and len(p["signals"])==8
assert not any(signals(p_a).values())
call(owner,f"/api/v1/finance/marketplaces/{uuid4()}/cost-sources/{a['id']}/identity-check",
     expected=404)
call(owner,root+"/"+str(uuid4())+"/identity-check",expected=404)
for x in (a,b):
    call(checker,root+"/"+x["id"]+"/review","POST",{
        "accept":True,"reason":"Independent synthetic identity metadata review"})

# Deliberate exact cross-family reference collision; the amounts being same
# did NOT cause this hold: only the identical normalized source key does.
sql(f"""INSERT INTO finance_ad_spend_reference(
 id,tenant_id,seller_marketplace_id,source_key,campaign_reference,
 business_date,currency_code,spend_amount,created_by)
 VALUES('{uuid4()}','{tenant}','{mp}','{key_a}','V165-LOCAL-CAMPAIGN',
 '2026-11-09','{currency}',9.000000,'{user}')""")
held=call(owner,url_a+"/identity-check")
assert held["status"]=="POTENTIAL_IDENTITY_COLLISION_HOLD"
assert held["approvalHeld"]
assert signals(held)["MANUAL_ADS_SPEND_SAME_KEY"]
# V166: persist exact candidate UUID, independent one-shot triage and audit.
# Triaging any of these local hypotheses must NEVER clear V165's hold.
cases_url=url_a+"/collision-cases"
call(sess(),cases_url,expected=401)
cases=call(owner,cases_url+"/scan","POST")
ads_cases=[x for x in cases if x["signalCode"]=="MANUAL_ADS_SPEND_SAME_KEY"]
assert len(ads_cases)==1
case=ads_cases[0]
assert case["state"]=="OPEN" and case["evidenceOrigin"]=="LOCAL_IDENTITY_ONLY"
assert not case["canClearFinancialHold"] and not case["externalProofVerified"]
assert len(case["candidateId"])==36
assert call(owner,cases_url+"/scan","POST")==cases  # idempotent, no audit replay
assert len(call(owner,cases_url))==len(cases)
case_review=cases_url+"/"+case["id"]+"/review"
call(owner,case_review,"POST",{"disposition":"POTENTIAL_DUPLICATE",
    "reason":"Maker cannot certify a potential duplicate"},expected=403)
call(checker,case_review,"POST",{"disposition":"CLEAR",
    "reason":"Unsafe attempt to clear unverified financial hold"},expected=400)
reviewed_case=call(checker,case_review,"POST",{
    "disposition":"POTENTIAL_DUPLICATE",
    "reason":"Independent triage found a matching local Ads reference only"})
assert reviewed_case["state"]=="POTENTIAL_DUPLICATE"
assert not reviewed_case["canClearFinancialHold"]
call(checker,case_review,"POST",{
    "disposition":"POTENTIAL_DUPLICATE",
    "reason":"Repeated review must not replace audit"},expected=409)
assert [x["action"] for x in call(owner,cases_url+"/"+case["id"]+"/audit")]==[
    "POTENTIAL_DUPLICATE","OPEN"]
assert call(owner,url_a+"/identity-check")["approvalHeld"]
call(checker,cases_url+"/scan","POST")
assert [x["action"] for x in call(owner,cases_url+"/"+case["id"]+"/audit")]==[
    "POTENTIAL_DUPLICATE","OPEN"]

assert not signals(call(owner,url_b+"/identity-check"))["MANUAL_ADS_SPEND_SAME_KEY"]
assert call(owner,url_b+"/identity-check")["status"]=="NO_LOCAL_IDENTITY_MATCH_UNVERIFIED"

def allocate(source):
    path=root+"/"+source["id"]+"/allocations"
    data={"requestKey":str(uuid4()),"targetKind":"PERIOD","currency":currency,
          "lines":[{"periodAfter":"2026-11-01","periodBefore":"2026-11-30",
                    "amount":"7.250000"}]}
    batch=call(owner,path,"POST",data)
    return path,batch["id"]
a_path,a_batch=allocate(a)
b_path,b_batch=allocate(b)
why={"accept":True,"reason":"Independent synthetic allocation evidence decision"}
call(checker,a_path+"/"+a_batch+"/decision","POST",why,expected=409)
assert call(owner,a_path)[0]["state"]=="PENDING_REVIEW"
assert [x["action"] for x in call(owner,a_path+"/"+a_batch+"/audit")]==["PROPOSED"]
good=call(checker,b_path+"/"+b_batch+"/decision","POST",why)
assert good["state"]=="APPROVED_UNVERIFIED"
assert good["summary"]["allocatedAmount"]=="7.250000"
assert good["summary"]["unallocatedAmount"]=="2.750000"
assert not good["sourceExternallyVerified"] and not good["recognizedProfitAllowed"]

# Later same-document evidence creates a potential hold in the *current*
# read-only snapshot. No retroactive claims of bank/GL clearance are made.
later=create("V165-DOC-"+uuid4().hex[:16],sha_b,"OTHER_OPERATING")
after_import=call(owner,url_b+"/identity-check")
assert after_import["approvalHeld"]
assert signals(after_import)["OTHER_MANUAL_COST_SAME_CLAIMED_DOCUMENT"]
late_cases=call(owner,url_b+"/collision-cases/scan","POST")
late=[x for x in late_cases
      if x["signalCode"]=="OTHER_MANUAL_COST_SAME_CLAIMED_DOCUMENT"]
assert len(late)==1
assert late[0]["state"]=="OPEN"
b_case_url=url_b+"/collision-cases/"+late[0]["id"]
unverified=call(checker,b_case_url+"/review","POST",{
    "disposition":"NOT_SAME_CHARGE_UNVERIFIED",
    "reason":"Manual triage cannot verify original invoice contents"})
assert unverified["state"]=="NOT_SAME_CHARGE_UNVERIFIED"
assert not unverified["canClearFinancialHold"]
assert call(owner,url_b+"/identity-check")["approvalHeld"]

assert call(owner,b_path)[0]["state"]=="APPROVED_UNVERIFIED"
assert not call(owner,b_path)[0]["recognizedProfitAllowed"]
assert sql(f"""SELECT count(*) FROM finance_cost_allocation_audit_v92
 WHERE tenant_id='{tenant}' AND batch_id='{a_batch}'""")=="1"
# Try to rewrite SQL case facts and audited decisions directly; DB must reject.
def sql_denied(q):
    p=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
        "-h","127.0.0.1","-U","merchivra","-d","merchivra","-Atc",q],
        capture_output=True,text=True,timeout=20,
        env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]})
    assert p.returncode!=0,"V166 immutable collision audit unexpectedly mutable"

sql_denied(f"UPDATE finance_cost_identity_case_v93 SET state='OPEN' "
           f"WHERE tenant_id='{tenant}' AND id='{case['id']}'")
sql_denied(f"DELETE FROM finance_cost_identity_case_v93 "
           f"WHERE tenant_id='{tenant}' AND id='{case['id']}'")
sql_denied(f"DELETE FROM finance_cost_identity_case_audit_v93 "
           f"WHERE tenant_id='{tenant}' AND case_id='{case['id']}'")
assert sql(f"SELECT count(*) FROM finance_cost_identity_case_audit_v93 "
           f"WHERE tenant_id='{tenant}' AND case_id='{case['id']}'")=="2"
# V167: actual PG18 Settlement REPORT LINE candidate, never real Amazon data.
# Synthetic LIVE-tag staging data are deliberately not external invoice proof.
report_id=str(uuid4())
report_key="V167-REPORT-"+uuid4().hex[:12]
doc_key="V167-DOCUMENT-"+uuid4().hex[:12]
doc_sha=uuid4().hex+uuid4().hex
sql(f"""INSERT INTO spapi_settlement_report (
 id,tenant_id,seller_marketplace_id,amazon_report_id,report_document_id,
 report_type,state,source_origin,document_sha256,settlement_reference,
 statement_currency,declared_net,line_count)
 VALUES ('{report_id}','{tenant}','{mp}','{report_key}','{doc_key}',
 'GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE_V2','IMPORTED','LIVE',
 '{doc_sha}','V167-SETTLEMENT-{uuid4().hex[:12]}','{currency}',-27,3)""")
sql(f"""INSERT INTO spapi_settlement_report_line(
 tenant_id,report_id,line_number,transaction_type,amount_type,
 amount_description,external_order_id,signed_amount,currency_code) VALUES
 ('{tenant}','{report_id}',1,'Other Transaction','Fee','Synthetic fee',
   '{key_a}',-10.000000,'{currency}'),
 ('{tenant}','{report_id}',2,'Other Transaction','Fee','Synthetic fee',
   '{key_b}',-9.000000,'{currency}'),
 ('{tenant}','{report_id}',3,'Other Transaction','Fee','Synthetic fee',
   'DIFFERENT-{uuid4().hex[:12]}',-8.000000,'{currency}')""")
link_url=url_a+"/settlement-line-links"
call(sess(),link_url,expected=401)
assert call(owner,link_url)==[]  # no link is UNKNOWN, not a zero charge
# Regular call helper includes owner CSRF; explicitly invoke without it to test.
saved_csrf=owner["csrf"]
owner["csrf"]=None
call(owner,link_url,"POST",{"reportId":report_id,"lineNumber":1},expected=403)
owner["csrf"]=saved_csrf
call(owner,link_url,"POST",{"reportId":report_id,"lineNumber":999},expected=404)
call(owner,link_url,"POST",{"reportId":str(uuid4()),"lineNumber":1},
     expected=404)
call(owner,link_url,"POST",{"reportId":report_id,"lineNumber":0},
     expected=400)
first=call(owner,link_url,"POST",{"reportId":report_id,"lineNumber":1})
assert first["similarity"]=="SIMILAR_REFERENCE_AND_AMOUNT"
assert first["state"]=="OPEN"
assert first["evidenceOrigin"]=="LOCAL_LINE_ASSOCIATION"
assert first["sourceAmount"]=="10.000000"
assert not first["confirmedDuplicate"] and not first["financialHoldCleared"]
assert not first["netProfitVerified"]
assert call(owner,link_url,"POST",{"reportId":report_id,
                                  "lineNumber":1})["id"]==first["id"]
assert len(call(owner,link_url))==1
review_url=link_url+"/"+first["id"]+"/review"
call(owner,review_url,"POST",{"disposition":"POTENTIAL_DUPLICATE_UNVERIFIED",
    "reason":"A source maker must not review the same evidence"},expected=403)
call(checker,review_url,"POST",{"disposition":"CLEAR",
    "reason":"Unsupported verified financial clearance"},expected=400)
decided=call(checker,review_url,"POST",{
    "disposition":"POTENTIAL_DUPLICATE_UNVERIFIED",
    "reason":"Identical order reference and amount still require invoice proof"})
assert decided["state"]=="POTENTIAL_DUPLICATE_UNVERIFIED"
assert not decided["financialHoldCleared"] and not decided["confirmedDuplicate"]
call(checker,review_url,"POST",{
    "disposition":"ESCALATED_NEEDS_PROOF",
    "reason":"The disposition must be single-use"},expected=409)
assert [x["action"] for x in call(owner,link_url+"/"+first["id"]+"/audit")]==[
    "POTENTIAL_DUPLICATE_UNVERIFIED","OPEN"]
assert call(owner,url_a+"/identity-check")["approvalHeld"], "V165 409 must remain"
b_link=url_b+"/settlement-line-links"
ref_only=call(owner,b_link,"POST",{"reportId":report_id,"lineNumber":2})
assert ref_only["similarity"]=="SIMILAR_REFERENCE_ONLY"
amount_only=call(owner,b_link,"POST",{"reportId":report_id,"lineNumber":1})
assert amount_only["similarity"]=="AMOUNT_ONLY_NO_IDENTITY"
unrelated=call(owner,b_link,"POST",{"reportId":report_id,"lineNumber":3})
assert unrelated["similarity"]=="UNRELATED_LINE_NO_EVIDENCE"
assert len(call(owner,b_link))==3
call(owner,f"/api/v1/finance/marketplaces/{uuid4()}"
     f"/cost-sources/{a['id']}/settlement-line-links",expected=404)
call(owner,root+"/"+str(uuid4())+"/settlement-line-links",expected=404)
sql_denied(f"UPDATE finance_cost_settlement_line_link_v94 "
           f"SET similarity='AMOUNT_ONLY_NO_IDENTITY' "
           f"WHERE tenant_id='{tenant}' AND id='{first['id']}'")
sql_denied(f"DELETE FROM finance_cost_settlement_line_link_v94 "
           f"WHERE tenant_id='{tenant}' AND id='{first['id']}'")
sql_denied(f"DELETE FROM finance_cost_settlement_line_audit_v94 "
           f"WHERE tenant_id='{tenant}' AND link_id='{first['id']}'")
assert sql(f"SELECT count(*) FROM finance_cost_settlement_line_audit_v94 "
           f"WHERE tenant_id='{tenant}' AND link_id='{first['id']}'")=="2"

# V168: a concrete report line can be linked to MULTIPLE independent manual
# costs. This is a dispute over lineage, not automatic proof of double debit.
c=create("V168-LINE-"+uuid4().hex[:16],uuid4().hex+uuid4().hex,"OTHER_OPERATING")
c_url=root+"/"+c["id"]
call(checker,c_url+"/review","POST",{
    "accept":True,"reason":"Independent synthetic manual source metadata review"})
before_c=call(owner,c_url+"/identity-check")
assert not before_c["approvalHeld"] and before_c["sharedSettlementLineCount"]==0
c_path,c_batch=allocate(c)
c_link=call(owner,c_url+"/settlement-line-links","POST",{
    "reportId":report_id,"lineNumber":2})  # line 2 already belongs to b
assert c_link["similarity"] in ("UNRELATED_LINE_NO_EVIDENCE",
                               "AMOUNT_ONLY_NO_IDENTITY")
shared=call(owner,c_url+"/identity-check")
assert shared["status"]=="POTENTIAL_SETTLEMENT_LINE_COLLISION_HOLD"
assert shared["approvalHeld"] and shared["linkedSettlementLineCount"]==1
assert shared["sharedSettlementLineCount"]==1
assert not shared["lineChargeOwnershipVerified"]
assert len(shared["signals"])==8 and not any(signals(shared).values())
drill=call(owner,c_url+"/identity-check/shared-settlement-lines")
assert drill["sharedLineCount"]==1 and drill["totalLinkedLines"]==1
assert drill["examplesTruncated"] is False
assert len(drill["examples"])==1
assert drill["examples"][0]["reportId"]==report_id
assert drill["examples"][0]["lineNumber"]==2
assert drill["examples"][0]["otherManualSourceCount"]==1
assert not drill["duplicateChargeVerified"] and not drill["accountingReleaseAllowed"]
assert call(owner,url_b+"/identity-check")["sharedSettlementLineCount"]>=1
call(checker,c_path+"/"+c_batch+"/decision","POST",{
    "accept":True,"reason":"Cannot approve the same settlement line twice"},expected=409)
assert call(owner,c_path)[0]["state"]=="PENDING_REVIEW"
# Even bypassing the app directly is rejected by the V95 database trigger.
sql_denied(f"""UPDATE finance_cost_allocation_batch_v92
 SET state='APPROVED_UNVERIFIED',reviewed_by='{peer['userId']}',
 reviewed_at=now(),review_reason='Independent synthetic direct SQL review'
 WHERE tenant_id='{tenant}' AND source_id='{c['id']}'
   AND id='{c_batch}'""")
sql_denied(f"""UPDATE spapi_settlement_report_line
 SET external_order_id='MUTATED-NOT-ALLOWED'
 WHERE tenant_id='{tenant}' AND report_id='{report_id}' AND line_number=2""")
sql_denied(f"""DELETE FROM spapi_settlement_report_line
 WHERE tenant_id='{tenant}' AND report_id='{report_id}' AND line_number=2""")
call(owner,f"/api/v1/finance/marketplaces/{uuid4()}"
    f"/cost-sources/{c['id']}/identity-check/shared-settlement-lines",
    expected=404)

after=[sql("SELECT count(*) FROM "+t) for t in
    ("finance_profit_report_job","bank_payout_observation","ads_report_reviewed_cost")]
assert before==after,"Manual identity scan may not post finance/bank/Ads"
print("PASS V165/V166/V167/V168 PG18+HTTP: 8-family source preview, audited "
      "settlement-line scoped links and independent unverified review; equal "
      "amount/different key NOT duplication; same Ads key holds approval 409; "
      "no self/unauthorized leak; later claimed-document collision signaled; "
      "all manual allocations remain unverified, no profit/bank/Ads posting")
