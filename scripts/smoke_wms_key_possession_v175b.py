#!/usr/bin/env python3
"""V175-B real PG18/HTTP disposable three-Owner Ed25519 possession workflow.

No real WMS, Seller/Ads, warehouse receipt, inventory, COGS or bank activity.
Only a temporary locally generated Ed25519 key; its private half never leaves
the GitHub runner, and it is removed after this synthetic smoke.
"""
import base64
import http.cookiejar
import json
import os
import subprocess
import tempfile
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import uuid4

assert os.environ.get("GITHUB_ACTIONS") == "true"
assert os.environ.get("MERCHIVRA_V175B_DISPOSABLE_ONLY") == "true"
assert os.environ.get("DB_HOST") == "127.0.0.1"
assert os.environ.get("DB_NAME") == "merchivra"
assert os.environ.get("MERCHIVRA_DEMO_ENABLED") == "true"
assert os.environ.get("MERCHIVRA_SPAPI_ENABLED") == "false"
assert os.environ.get("MERCHIVRA_SPAPI_OAUTH_ENABLED") == "false"
assert os.environ.get("MERCHIVRA_SPAPI_ALLOW_WRITES", "false") == "false"
BASE = "http://127.0.0.1:8080"

def sql(statement):
    proc = subprocess.run([
        "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
        "-h", "127.0.0.1", "-U", "merchivra", "-d", "merchivra", "-Atc", statement],
        env={**os.environ, "PGPASSWORD":os.environ["DB_PASSWORD"]},
        capture_output=True, text=True, timeout=20)
    assert proc.returncode == 0, "V175-B synthetic PG18 fixture error"
    return proc.stdout.strip()

def client():
    return {"agent":build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar())),
            "csrf":None}

def call(c,path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    if c["csrf"] and method=="POST":
        headers["X-CSRF-TOKEN"]=c["csrf"]
    payload=None
    if body is not None:
        payload=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]=("application/x-www-form-urlencoded" if form
                                 else "application/json")
    try:
        with c["agent"].open(Request(BASE+path,method=method,data=payload,
            headers=headers),timeout=25) as r:
            status,raw,cache=r.status,r.read(),r.headers.get("Cache-Control","")
    except HTTPError as e:
        status,raw,cache=e.code,e.read(),e.headers.get("Cache-Control","")
    assert status==expected,(method,path,status,expected,raw[:250])
    if status==200 and "/key-possession" in path:
        assert "no-store" in cache.lower()
    try:return json.loads(raw)
    except (ValueError,TypeError):return None

def csrf(c):
    c["csrf"]=call(c,"/api/v1/auth/csrf")["token"]

def login(c,username,password):
    csrf(c)
    call(c,"/api/v1/auth/login","POST",
         {"username":username,"password":password},form=True)
    csrf(c)
    return call(c,"/api/v1/auth/me")

assert sql("SHOW server_version_num").startswith("18")
assert sql("SELECT count(*) FROM flyway_schema_history WHERE version='101' AND success")=="1"
assert sql("""SELECT count(*) FROM pg_trigger WHERE tgname IN
 ('finance_wms_key_proof_actor_v101','finance_wms_key_challenge_immutable_v101',
  'finance_wms_key_proof_immutable_v101') AND NOT tgisinternal""")=="3"
assert sql("""SELECT count(*) FROM pg_constraint WHERE conname IN
 ('finance_wms_key_challenge_v101_tenant_id_nonce_hex_key',
  'finance_wms_key_proof_v101_tenant_id_challenge_id_key')""")=="2"
before=[sql("SELECT count(*) FROM "+t) for t in
 ("finance_profit_report_job","erp_local_stock_movement",
  "bank_payout_observation","ads_report_reviewed_cost")]
admin,approver,verifier=client(),client(),client()
me=login(admin,os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
         os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"])
tenant=me["activeTenantId"]
assert me["isOwner"] and tenant
def extra(owner):
    name="v175b"+uuid4().hex[:15]
    invite=call(admin,"/api/v1/team/invitations","POST",
        {"loginName":name,"role":"OWNER"})
    csrf(owner)
    password="v175b-synthetic-independent-owner-only"
    call(owner,"/api/v1/auth/invitations/accept","POST",
        {"token":invite["token"],"password":password},expected=204)
    user=login(owner,name,password)
    assert user["isOwner"] and user["activeTenantId"]==tenant
    return user["userId"]
approver_id=extra(approver)
verifier_id=extra(verifier)
assert len({me["userId"],approver_id,verifier_id})==3
nonce=uuid4().hex[:12].upper()
warehouse=str(uuid4())
sql(f"""INSERT INTO erp_local_warehouse
 (id,tenant_id,warehouse_code,display_name,status,created_by)
 VALUES ('{warehouse}','{tenant}','V175B-{nonce}',
 'Disposable synthetic key possession warehouse','ACTIVE','{me["userId"]}')""")
source_root="/api/v1/finance/wms-signing-sources"
with tempfile.TemporaryDirectory(prefix="v175b-fake-ed25519-") as tmp:
    private=Path(tmp)/"private.pem"
    message=Path(tmp)/"payload.txt"
    signed=Path(tmp)/"signature.bin"
    subprocess.run(["openssl","genpkey","-algorithm","ED25519",
                    "-out",str(private)],check=True,capture_output=True)
    public=subprocess.run(["openssl","pkey","-in",str(private),
        "-pubout","-outform","DER"],check=True,capture_output=True).stdout
    spki=base64.b64encode(public).decode()
    source=call(admin,source_root,"POST",{
        "warehouseId":warehouse,"sourceCode":"KEYCTRL_"+nonce,
        "publicKeySpkiBase64":spki,
        "reason":"Disposable public key proves no real operator identity"})
    assert source["state"]=="PENDING" and not source["operatorIdentityVerified"]
    source_id=source["id"]
    url=source_root+"/"+source_id
    call(admin,url+"/approve","POST",{
        "reason":"Self approval is not independent and must be rejected"},
        expected=403)
    result=call(approver,url+"/approve","POST",{
        "reason":"Second Owner approves only synthetic tenant enrolled public key"})
    assert result["state"]=="APPROVED"
    root=url+"/key-possession"
    call(client(),root,expected=401)
    call(admin,source_root+"/"+str(uuid4())+"/key-possession",expected=404)
    assert call(admin,root)==[]
    challenge=call(approver,root+"/challenges","POST")
    assert challenge["status"]=="AWAITING_SIGNATURE"
    assert challenge["sourceId"]==source_id
    assert challenge["warehouseId"]==warehouse
    assert len(challenge["nonceHex"])==64
    assert challenge["canonicalPayload"].endswith("\n")
    assert challenge["canonicalPayload"].startswith(
        "MERCHIVRA_WMS_KEY_POSSESSION_V1\n"+tenant+"\n")
    assert challenge["issuedBy"]==approver_id
    assert not challenge["operatorIdentityVerified"]
    assert not challenge["physicalReceiptVerified"]
    assert not challenge["inventoryPosted"]
    assert not challenge["cogsReversalEligible"]
    assert challenge["actualCogsReversal"] is None
    call(admin,root+"/challenges","POST",expected=409)
    verify_url=root+"/challenges/"+challenge["id"]+"/verify"
    call(admin,verify_url,"POST",{
        "signatureBase64":base64.b64encode(bytes(64)).decode()},expected=403)
    call(approver,verify_url,"POST",{
        "signatureBase64":base64.b64encode(bytes(64)).decode()},expected=403)
    call(verifier,verify_url,"POST",{
        "signatureBase64":base64.b64encode(bytes(64)).decode()},expected=409)
    message.write_bytes(challenge["canonicalPayload"].encode("utf-8"))
    subprocess.run(["openssl","pkeyutl","-sign","-inkey",str(private),
        "-rawin","-in",str(message),"-out",str(signed)],
        check=True,capture_output=True)
    signature=base64.b64encode(signed.read_bytes()).decode()
    assert len(signed.read_bytes())==64
    proof=call(verifier,verify_url,"POST",{"signatureBase64":signature})
    assert proof["status"]=="KEY_POSSESSION_ONLY"
    assert proof["signatureValid"]
    assert proof["verifiedBy"]==verifier_id
    assert not proof["operatorIdentityVerified"]
    assert not proof["physicalReceiptVerified"]
    assert not proof["inventoryPosted"] and not proof["cogsReversalEligible"]
    assert proof["actualCogsReversal"] is None
    call(verifier,verify_url,"POST",{"signatureBase64":signature},expected=409)
    assert len(call(admin,root))==1
    assert sql(f"""SELECT count(*) FROM finance_wms_key_proof_v101
        WHERE tenant_id='{tenant}' AND challenge_id='{challenge["id"]}'
          AND result='KEY_POSSESSION_ONLY' AND operator_identity_verified=FALSE
          AND physical_receipt_verified=FALSE AND inventory_posted=FALSE
          AND cogs_reversed=FALSE""")=="1"
    # Tenant-approved revocation invalidates readiness but preserves evidence.
    revoked=call(approver,url+"/revoke","POST",{
        "reason":"Independent reviewer revokes disposable test-only signing source"})
    assert revoked["state"]=="REVOKED"
    held=call(admin,root)[0]
    assert held["status"]=="SOURCE_REVOKED_HOLD"
    assert held["signatureValid"] and not held["sourceActive"]
    call(admin,root+"/challenges","POST",expected=409)
after=[sql("SELECT count(*) FROM "+t) for t in
 ("finance_profit_report_job","erp_local_stock_movement",
  "bank_payout_observation","ads_report_reviewed_cost")]
assert before==after,"WMS possession workflow MUST NOT mutate stock, profit, bank or Ads"
print("PASS V175-B real PG18 V101 + three independent Owner HTTP/Spring CSRF, "
      "Ed25519 exact payload, tampering/replay/source revocation 403/409, "
      "immutable untrusted signature, zero stock/COGS/profit, Amazon OFF")
