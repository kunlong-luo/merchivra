#!/usr/bin/env python3
"""V230 true PG18 Owner HTTP category-specific external-proof dossier.

Only disposable GitHub main CI with NO real Seller/Ads OAuth or external
invoice/bank issuer. Synthetic V91 overlap and damaged-audit fixtures are
negative tests; never use this script on a real tenant or production database.
"""
import http.cookiejar
import json
import os
import subprocess
import urllib.parse
import urllib.request
from urllib.error import HTTPError
from uuid import UUID, uuid4

REQUIRED={
    "MERCHIVRA_V230_DISPOSABLE_ONLY":"true",
    "MERCHIVRA_V229_DISPOSABLE_ONLY":"true",
    "MERCHIVRA_V226_DISPOSABLE_ONLY":"true",
    "MERCHIVRA_V225_DISPOSABLE_ONLY":"true",
    "MERCHIVRA_V222_DISPOSABLE_ONLY":"true",
    "SPRING_PROFILES_ACTIVE":"ci-v222",
    "GITHUB_ACTIONS":"true",
    "GITHUB_REPOSITORY":"kunlong-luo/merchivra",
    "GITHUB_REF":"refs/heads/main",
    "MERCHIVRA_SPAPI_ENABLED":"false",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED":"false",
    "DB_HOST":"127.0.0.1","DB_PORT":"5432",
    "DB_NAME":"merchivra","DB_USER":"merchivra",
    "DB_PASSWORD":"ci_disposable_database_password",
    "PGPASSWORD":"ci_disposable_database_password",
    "MERCHIVRA_BOOTSTRAP_USERNAME":"v65-ci-owner",
    "MERCHIVRA_BOOTSTRAP_PASSWORD":"ci-disposable-v65-only-secret-0000"
}
for name,expected in REQUIRED.items():
    assert os.environ.get(name)==expected, "V230 restricted disposable CI: "+name
assert os.environ.get("GITHUB_RUN_ID","").isdigit()
PARGS=["psql","-X","-qAt","-h","127.0.0.1","-U","merchivra",
       "-d","merchivra","-v","ON_ERROR_STOP=1"]
def pg(sql):
    r=subprocess.run(PARGS+["-c",sql],capture_output=True,text=True,timeout=18)
    assert r.returncode==0,("V230 real PG18 SQL failed",r.stderr[-1000:])
    return r.stdout.strip()
def n(sql):
    return int(pg(sql).splitlines()[-1])
TENANT=str(UUID(pg("""
SELECT m.tenant_id FROM membership m
JOIN local_credential c ON c.user_id=m.user_id
JOIN role_binding b ON b.tenant_id=m.tenant_id AND b.user_id=m.user_id
WHERE c.login_name='v65-ci-owner' AND b.role_code='OWNER'
  AND b.seller_account_id IS NULL AND m.status='ACTIVE';
""")))
MP="00000000-0000-4000-8000-000000005590"
OTHER="00000000-0000-4000-8000-000000005593"
assert n("SELECT count(*) FROM seller_marketplace "
         f"WHERE tenant_id='{TENANT}' AND id='{MP}'")==1
assert n("SELECT count(*) FROM pg_trigger "
         "WHERE tgname='finance_cost_source_audit_append_only_v92'")==1
def epoch():
    return n("SELECT coalesce((SELECT revision FROM "
             f"finance_receipt_read_epoch_v113 WHERE tenant_id='{TENANT}'),0)")

class Browser:
    def __init__(self):
        self.opener=urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def call(self,path,method="GET",body=None,csrf=None):
        headers={"Accept":"application/json"}
        data=None
        if body is not None:
            headers["Content-Type"]="application/json"
            data=json.dumps(body).encode()
        if csrf: headers["X-CSRF-TOKEN"]=csrf
        req=urllib.request.Request("http://127.0.0.1:8080"+path,
            method=method,data=data,headers=headers)
        try:
            with self.opener.open(req,timeout=24) as r:
                status,raw,head=r.status,r.read(),r.headers
        except HTTPError as e:
            status,raw,head=e.code,e.read(),e.headers
        parsed=(json.loads(raw) if raw and
                head.get_content_type()=="application/json" else None)
        return status,parsed,head,raw
    def csrf(self):
        status,obj,_,_=self.call("/api/v1/auth/csrf")
        assert status==200 and obj["headerName"]=="X-CSRF-TOKEN"
        return obj["token"]
    def login(self):
        request=urllib.request.Request(
            "http://127.0.0.1:8080/api/v1/auth/login",
            data=urllib.parse.urlencode({
                "username":"v65-ci-owner",
                "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]
            }).encode(),
            method="POST",headers={
                "Content-Type":"application/x-www-form-urlencoded",
                "Accept":"application/json",
                "X-CSRF-TOKEN":self.csrf()})
        with self.opener.open(request,timeout=20) as r: assert r.status==200
        status,me,_,_=self.call("/api/v1/auth/me")
        assert status==200 and me["isOwner"] and me["activeTenantId"]==TENANT
owner=Browser()
owner.login()
batch=f"/api/v1/finance/marketplaces/{MP}/cost-sources/batch"
def draft(category,sha=None):
    u=uuid4().hex
    return {"category":category,"sourceKind":"MANUAL_OTHER",
            "sourceKey":"V230-PROOF-"+u,"documentSha256":sha or (u*2),
            "businessDate":"2026-11-24","amount":"14.750000","currency":"USD"}
original=[draft("ADS_SPEND"),draft("INBOUND_FREIGHT"),
          draft("FBA_STORAGE"),draft("RETURN_HANDLING")]
before=epoch()
status,sources,head,raw=owner.call(batch,"POST",original,owner.csrf())
assert status==200 and len(sources)==len(original),(status,raw[:400])
assert len({r["id"] for r in sources})==len(sources)
assert epoch()-before==2
ids={r["category"]:r["id"] for r in sources}

def url(ident,mp=MP):
    return (f"/api/v1/finance/marketplaces/{mp}/cost-sources/"
            f"{ident}/external-proof-dossier")
def inspect(ident,category,requirement,expected_status=None):
    status,report,headers,raw=owner.call(url(ident))
    assert status==200 and isinstance(report,dict),(status,raw[:400])
    assert "no-store" in headers.get("Cache-Control","").lower()
    assert report["sourceId"]==ident and report["marketplaceId"]==MP
    assert report["category"]==category and report["originalCurrency"]=="USD"
    assert report["sourceReviewState"]=="STAGED"
    if expected_status:
        assert report["localDossierStatus"]==expected_status,report
    required=report["requiredExternalProof"]
    codes={item["code"] for item in required}
    assert requirement in codes,codes
    assert "ORIGINAL_DOCUMENT_BYTES_AND_SHA256" in codes
    assert "INDEPENDENT_ISSUER_AUTHORITY" in codes
    assert "INDEPENDENT_PAYMENT_AND_DUPLICATE_REVIEW" in codes
    assert all(item["independentlyVerified"] is False for item in required)
    assert codes<=set(report["blockerCodes"])
    for field in ("originalBytesVerified","issuerIdentityVerified",
                  "originalChargeVerified","duplicateDebitExcluded",
                  "settlementOrPaymentReconciled",
                  "actualLandedOrRefundCogsVerified",
                  "accountingPolicyApproved","eligibleForRealizedProfit"):
        assert report[field] is False,field
    assert report["realizedNetProfit"] is None
    return report

requirements={
 "ADS_SPEND":"AMAZON_ADS_REPORT_AND_BILLING_LINE",
 "INBOUND_FREIGHT":"CARRIER_INVOICE_SHIPMENT_AND_RECEIPT",
 "FBA_STORAGE":"AMAZON_FBA_FEE_REPORT_LINE",
 "RETURN_HANDLING":"RETURN_DISPOSITION_RECEIPT_AND_COGS_REVERSAL"
}
start=epoch()
for category,ident in ids.items():
    result=inspect(ident,category,requirements[category],
      "LOCAL_METADATA_REVIEW_PENDING")
    assert result["localSourceAuditComplete"] is True
    assert result["independentMetadataReviewComplete"] is False
assert epoch()==start, "V230 must be read-only, no tenant epoch ticks"
assert Browser().call(url(ids["ADS_SPEND"]))[0]==401
assert owner.call(url(ids["ADS_SPEND"],OTHER))[0]==404
assert owner.call(url(str(uuid4())))[0]==404

# A local matching digest can be flagged as a *candidate* collision,
# never "proven duplicate debit". This must not suppress category proof gaps.
collision_sha=uuid4().hex*2
duplicates=[draft("INBOUND_FREIGHT",collision_sha),
            draft("INBOUND_FREIGHT",collision_sha)]
status,two,_,raw=owner.call(batch,"POST",duplicates,owner.csrf())
assert status==200 and len(two)==2,(status,raw[:300])
collision=inspect(two[0]["id"],"INBOUND_FREIGHT",
                  "CARRIER_INVOICE_SHIPMENT_AND_RECEIPT")
assert collision["localCollisionHeld"] is True
assert "V165_V94_LOCAL_CROSS_SOURCE_COLLISION_HOLD" in collision["blockerCodes"]

# Source without initial stage audit is possible with a faulty DB writer.
# Only disposable PG18 can inject this negative test, with trigger intact.
orphan=str(uuid4())
key="V230-ORPHAN-"+uuid4().hex
pg(f"""
INSERT INTO finance_cost_source_v91
  (id,tenant_id,seller_marketplace_id,category,source_kind,source_key,
   source_document_sha256,business_date,currency_code,amount,created_by)
SELECT '{orphan}',tenant_id,seller_marketplace_id,category,source_kind,
  '{key}',source_document_sha256,business_date,currency_code,amount,created_by
FROM finance_cost_source_v91
WHERE tenant_id='{TENANT}' AND seller_marketplace_id='{MP}'
  AND id='{ids["INBOUND_FREIGHT"]}';
""")
missing=inspect(orphan,"INBOUND_FREIGHT",
    "CARRIER_INVOICE_SHIPMENT_AND_RECEIPT",
    "LOCAL_AUDIT_INCONSISTENT_HOLD")
assert missing["localSourceAuditComplete"] is False
assert "V91_SOURCE_AUDIT_INCONSISTENT" in missing["blockerCodes"]

# A read-only dossier must also identify original source audit duplicates.
pg(f"""
INSERT INTO finance_cost_source_audit_v91
  (id,tenant_id,source_id,action,actor_id)
SELECT '{uuid4()}','{TENANT}',id,'STAGED',created_by
FROM finance_cost_source_v91
WHERE tenant_id='{TENANT}' AND id='{ids["ADS_SPEND"]}';
""")
extra=inspect(ids["ADS_SPEND"],"ADS_SPEND",
              "AMAZON_ADS_REPORT_AND_BILLING_LINE",
              "LOCAL_AUDIT_INCONSISTENT_HOLD")
assert extra["localSourceAuditComplete"] is False
assert "V91_SOURCE_AUDIT_INCONSISTENT" in extra["blockerCodes"]

print("V230_SOURCE_PROOF_DOSSIER "+json.dumps({
    "categories":list(requirements),
    "source_audit_cases":"1 intact, 0 missing, 2 duplicate",
    "v165_duplicate_document_candidate_held":True,
    "owner_anonymous_401":True,"wrong_marketplace_404":True,
    "read_only_epoch_ticks":0,
    "all_external_attestation_false_profit_null":True,
    "disposable_pg18_only":True},sort_keys=True),flush=True)
print("PASS V230 real PG18 Owner per-category external proof requirements, "
      "V91 1/0/2 audit precedence, V165 collision hold, 401/404, "
      "zero read ticks and no external charge/COGS/profit verification",flush=True)
