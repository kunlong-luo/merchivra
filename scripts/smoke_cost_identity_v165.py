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
a=create(key_a,"b","ADS_SPEND")
b=create(key_b,"c","INBOUND_FREIGHT")
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
later=create("V165-DOC-"+uuid4().hex[:16],"c","OTHER_OPERATING")
after_import=call(owner,url_b+"/identity-check")
assert after_import["approvalHeld"]
assert signals(after_import)["OTHER_MANUAL_COST_SAME_CLAIMED_DOCUMENT"]
assert call(owner,b_path)[0]["state"]=="APPROVED_UNVERIFIED"
assert not call(owner,b_path)[0]["recognizedProfitAllowed"]
assert sql(f"""SELECT count(*) FROM finance_cost_allocation_audit_v92
 WHERE tenant_id='{tenant}' AND batch_id='{a_batch}'""")=="1"
after=[sql("SELECT count(*) FROM "+t) for t in
    ("finance_profit_report_job","bank_payout_observation","ads_report_reviewed_cost")]
assert before==after,"Manual identity scan may not post finance/bank/Ads"
print("PASS V165 PG18+HTTP: scoped 8-family source identity preview; equal "
      "amount/different key NOT duplication; same Ads key holds approval 409; "
      "no self/unauthorized leak; later claimed-document collision signaled; "
      "all manual allocations remain unverified, no profit/bank/Ads posting")
