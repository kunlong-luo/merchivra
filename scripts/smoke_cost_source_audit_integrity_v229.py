#!/usr/bin/env python3
"""V229: real Spring Owner HTTP + PG18 V91 audit continuity read preflight.

Strict disposable main-only CI fixtures. No external invoice/issuer proof,
no real COGS or accounting posting. All bad records exist only in this CI DB.
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
    "MERCHIVRA_V229_DISPOSABLE_ONLY": "true",
    "MERCHIVRA_V226_DISPOSABLE_ONLY": "true",
    "MERCHIVRA_V225_DISPOSABLE_ONLY": "true",
    "MERCHIVRA_V222_DISPOSABLE_ONLY": "true",
    "SPRING_PROFILES_ACTIVE": "ci-v222",
    "GITHUB_ACTIONS": "true",
    "GITHUB_REPOSITORY": "kunlong-luo/merchivra",
    "GITHUB_REF": "refs/heads/main",
    "MERCHIVRA_SPAPI_ENABLED": "false",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED": "false",
    "DB_HOST": "127.0.0.1", "DB_PORT": "5432",
    "DB_NAME": "merchivra", "DB_USER": "merchivra",
    "DB_PASSWORD": "ci_disposable_database_password",
    "PGPASSWORD": "ci_disposable_database_password",
    "MERCHIVRA_BOOTSTRAP_USERNAME": "v65-ci-owner",
    "MERCHIVRA_BOOTSTRAP_PASSWORD": "ci-disposable-v65-only-secret-0000",
}
for key,value in REQUIRED.items():
    assert os.environ.get(key)==value,"V229 disposable main only: "+key
assert os.environ.get("GITHUB_RUN_ID","").isdigit()

PARGS=["psql","-X","-qAt","-h","127.0.0.1","-U","merchivra",
       "-d","merchivra","-v","ON_ERROR_STOP=1"]
def pg(sql):
    proc=subprocess.run(PARGS+["-c",sql],capture_output=True,text=True,timeout=18)
    assert proc.returncode==0,("V229 PG18 SQL failure",proc.stderr[-750:])
    return proc.stdout.strip()
def number(sql):
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
assert number("SELECT count(*) FROM seller_marketplace "
              f"WHERE tenant_id='{TENANT}' AND id='{MP}'") == 1
assert number("SELECT count(*) FROM pg_trigger "
              "WHERE tgname='finance_cost_source_audit_append_only_v92'") == 1
def epoch():
    return number("SELECT revision FROM finance_receipt_read_epoch_v113 "
                  f"WHERE tenant_id='{TENANT}'")
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
        if csrf:
            headers["X-CSRF-TOKEN"]=csrf
        req=urllib.request.Request("http://127.0.0.1:8080"+path,method=method,
                                   data=data,headers=headers)
        try:
            with self.opener.open(req,timeout=22) as r:
                status,raw,h=r.status,r.read(),r.headers
        except HTTPError as e:
            status,raw,h=e.code,e.read(),e.headers
        value=(json.loads(raw) if raw and
               h.get_content_type()=="application/json" else None)
        return status,value,h,raw
    def token(self):
        status,content,_,_=self.call("/api/v1/auth/csrf")
        assert status==200 and content["headerName"]=="X-CSRF-TOKEN"
        return content["token"]
    def login(self):
        req=urllib.request.Request("http://127.0.0.1:8080/api/v1/auth/login",
            data=urllib.parse.urlencode({"username":"v65-ci-owner",
                "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]}).encode(),
            method="POST",headers={"Content-Type":
            "application/x-www-form-urlencoded","Accept":"application/json",
            "X-CSRF-TOKEN":self.token()})
        with self.opener.open(req,timeout=20) as r: assert r.status==200
        status,me,_,_=self.call("/api/v1/auth/me")
        assert status==200 and me["isOwner"] and me["activeTenantId"]==TENANT

owner=Browser()
owner.login()
batch=f"/api/v1/finance/marketplaces/{MP}/cost-sources/batch"
def draft():
    u=uuid4().hex
    return {"category":"INBOUND_FREIGHT","sourceKind":"MANUAL_OTHER",
            "sourceKey":"V229-AUDIT-"+u,"documentSha256":u*2,
            "businessDate":"2026-11-24","amount":"12.340000","currency":"USD"}
status,created,_,raw=owner.call(batch,"POST",[draft()],owner.token())
assert status==200 and len(created)==1,(status,raw[:240])
source=str(UUID(created[0]["id"]))
def route(ident,marketplace=MP):
    return (f"/api/v1/finance/marketplaces/{marketplace}/"
            f"cost-sources/{ident}/audit-integrity")
def verify(ident,expected_complete,expected_audit_count,expected_reason):
    status,data,headers,raw=owner.call(route(ident))
    assert status==200 and data is not None,(status,raw[:280])
    assert "no-store" in headers.get("Cache-Control","").lower()
    assert data["sourceId"]==ident and data["marketplaceId"]==MP
    assert data["reviewState"]=="STAGED"
    assert data["auditEventCount"]==expected_audit_count
    assert data["localAuditComplete"] is expected_complete
    assert (expected_reason in data["blockerCodes"])
    for prop in ("claimedDocumentBytesVerified",
                 "independentExternalIssuerVerified","externalChargeVerified",
                 "actualCogsVerified","eligibleForRealizedProfit"):
        assert data[prop] is False, prop
    assert data["realizedNetProfit"] is None
    assert "INDEPENDENT_EXTERNAL_ISSUER_NOT_ATTESTED" in data["blockerCodes"]
    return data

baseline=epoch()
valid=verify(source,True,1,"CLAIMED_DOCUMENT_SHA256_NOT_BYTES_VERIFIED")
assert valid["stageAuditIntact"] is True
assert valid["independentDecisionAuditIntact"] is False
assert epoch()==baseline,"V229 read-only GET cannot increment epoch"

# Genuine anonymous and foreign Marketplace requests must not reveal sources.
assert Browser().call(route(source))[0]==401
assert owner.call(route(source,OTHER))[0]==404
assert owner.call(route(str(uuid4())))[0]==404

# Append-only V91 is not equivalent to a complete history. Insert a synthetic
# source with no STAGED audit through disposable PG18 fixture-only SQL.
orphan=str(uuid4())
orphan_key="V229-ORPHAN-"+uuid4().hex
pg(f"""
INSERT INTO finance_cost_source_v91
 (id,tenant_id,seller_marketplace_id,category,source_kind,source_key,
  source_document_sha256,business_date,currency_code,amount,created_by)
SELECT '{orphan}',tenant_id,seller_marketplace_id,category,source_kind,
  '{orphan_key}',source_document_sha256,business_date,currency_code,
  amount,created_by FROM finance_cost_source_v91
WHERE tenant_id='{TENANT}' AND seller_marketplace_id='{MP}'
AND id='{source}';
""")
invalid=verify(orphan,False,0,"V91_STAGED_AUDIT_MISSING_OR_DUPLICATED")
assert "V91_SOURCE_AUDIT_INCONSISTENT" in invalid["blockerCodes"]

# One extra STAGED audit can be appended without violating the V92 append-only
# trigger; V229 must still reject the duplicated audit as incomplete.
pg(f"""
INSERT INTO finance_cost_source_audit_v91
(id,tenant_id,source_id,action,actor_id)
SELECT '{uuid4()}','{TENANT}',id,'STAGED',created_by
FROM finance_cost_source_v91 WHERE tenant_id='{TENANT}' AND id='{source}';
""")
duplicate=verify(source,False,2,"V91_SOURCE_AUDIT_INCONSISTENT")
assert duplicate["stageAuditIntact"] is False
assert epoch()>baseline

print("V229_REAL_OWNER_AUDIT " + json.dumps({
    "valid_staged_audit_events":1,"missing_stage_audit_events":0,
    "duplicate_stage_audit_events":2,"source_same_tenant_foreign_mp_404":True,
    "no_session_401":True,"read_epoch_unchanged":True,
    "external_issuer_charge_cogs_profit_all_unverified":True,
    "disposable_pg18_only":True},sort_keys=True),flush=True)
print("PASS V229 real PG18 Owner V91 source audit completeness: valid 1, "
      "missing 0, duplicate 2, 401/404 no-store, no external proof/COGS/profit")
