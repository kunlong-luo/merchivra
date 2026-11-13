#!/usr/bin/env python3
"""V75 real PG18 HTTP: explicit human PO/ASN <-> local/mock FBA shipment identity.
Disposable localhost only. No Amazon seller/ads write, payment or external tracking.
"""
import http.cookiejar
import json
import os
import subprocess
from datetime import datetime,timedelta,timezone
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,HTTPCookieProcessor,build_opener
from uuid import uuid4

assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
host="http://127.0.0.1:8080"
owner=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=None
def call(url,method="GET",body=None,expected=200,form=False,browser=owner,token=None,headers=None):
    h={"Accept":"application/json",**(headers or {})}
    payload=None
    if body is not None:
        payload=(urlencode(body) if form else json.dumps(body)).encode()
        h["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and (token or csrf):h["X-CSRF-TOKEN"]=token or csrf
    try:
        with browser.open(Request(host+url,method=method,data=payload,headers=h),timeout=25) as r:
            status,data=r.status,r.read()
    except HTTPError as e:status,data=e.code,e.read()
    assert status==expected,(method,url,status,expected,data[:600])
    try:return json.loads(data)
    except ValueError:return data
def post(url,body=None,expected=200,browser=owner,token=None,headers=None):
    return call(url,"POST",body if body is not None else {},expected,
                browser=browser,token=token,headers=headers)
def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    r=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1",
      "-h","127.0.0.1","-p",os.getenv("DB_PORT","5432"),
      "-U","merchivra","-d","merchivra","-Atc",statement],
      env=env,text=True,capture_output=True)
    assert r.returncode==0,r.stderr[-1100:]
    return r.stdout.strip()

unowned=str(uuid4())
call("/api/v1/fba/marketplaces/"+unowned+"/shipments/"+str(uuid4())+"/purchase-links",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me");tenant=me["activeTenantId"]
assert me["isOwner"]
call("/api/v1/fba/marketplaces/"+unowned+"/shipments/"+str(uuid4())+"/purchase-links",
 expected=404)

account=post("/api/v1/seller-accounts",{"displayName":"V75 Synthetic","region":"NA"},
 201,headers={"Idempotency-Key":"v75-idproof-test-account"})["id"]
market=post("/api/v1/seller-accounts/"+account+"/marketplaces",
 {"marketplaceId":"ATVPDKIKX0DER"},201)["id"]
fba=f"/api/v1/fba/marketplaces/{market}/shipments"
sku="V75-PROOF-SKU"
product,listing,mapping=[str(uuid4()) for _ in range(3)]
sql(f"""INSERT INTO product_sku(id,tenant_id,internal_sku,display_name)
 VALUES('{product}','{tenant}','V75-PRODUCT','V75 proof fictional item');
 INSERT INTO marketplace_listing(id,tenant_id,seller_marketplace_id,
 seller_sku,mapping_status,data_origin)
 VALUES('{listing}','{tenant}','{market}','{sku}','ACTIVE','MOCK');
 INSERT INTO listing_sku_mapping
 (id,tenant_id,listing_id,product_sku_id,status,valid_from,mapping_version)
 VALUES('{mapping}','{tenant}','{listing}','{product}','ACTIVE',now(),1);""")
supplier=post("/api/v1/purchasing/suppliers",{
 "supplierCode":"V75SUPPLIER","displayName":"V75 Fictional Supplier"},201)["id"]
warehouse=post("/api/v1/purchasing/warehouses",{
 "warehouseCode":"V75LOCAL","displayName":"V75 Fictional Warehouse"},201)["id"]
po=post("/api/v1/purchasing/orders",{
 "supplierId":supplier,"currency":"USD","notes":"V75 synthetic proof",
 "lines":[{"productSkuId":product,"quantity":20,"unitCost":"1.300000"}]},201)
order=po["order"]["id"];poLine=po["lines"][0]["id"]
post("/api/v1/purchasing/orders/"+order+"/submit",{})
invite=post("/api/v1/team/invitations",{"loginName":"v75-second-owner"})
second=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
token=call("/api/v1/auth/csrf",browser=second)["token"]
passwd="v75-ci-different-owner-disposable-credential"
call("/api/v1/auth/invitations/accept","POST",
 {"token":invite["token"],"password":passwd},204,browser=second,token=token)
token=call("/api/v1/auth/csrf",browser=second)["token"]
call("/api/v1/auth/login","POST",{
 "username":"v75-second-owner","password":passwd},form=True,browser=second,token=token)
token=call("/api/v1/auth/csrf",browser=second)["token"]
assert call("/api/v1/auth/me",browser=second)["userId"]!=me["userId"]
post("/api/v1/purchasing/orders/"+order+"/approve",{},
 browser=second,token=token)
eta=(datetime.now(timezone.utc).date()+timedelta(days=7)).isoformat()
asn=post("/api/v1/purchasing/supplier-asns/orders/"+order,{
 "requestKey":str(uuid4()),"lineId":poLine,"warehouseId":warehouse,
 "quantity":18,"expectedArrivalDate":eta,"trackingReference":"V75-MOCK-NOT-CARRIER"},201)
asnId=asn["id"]
post("/api/v1/purchasing/supplier-asns/orders/"+order+"/"+asnId+"/ship",{})

def shipment(ref,quantity):
    value=post(fba,{"requestKey":str(uuid4()),"reference":ref,
     "origin":"MOCK","lines":[{"sellerSku":sku,"quantity":quantity}]})
    return value["shipment"]["id"],value["lines"][0]["id"]
first,line=shipment("V75-FBA-MOCK-001",12)
route=fba+"/"+first+"/purchase-links"
assert call(route)==[]
candidates=call(route+"/lines/"+line+"/candidates")
assert len(candidates)==1 and candidates[0]["purchaseOrderLineId"]==poLine,candidates
assert candidates[0]["asnId"]==asnId,candidates
assert candidates[0]["orderedQuantity"]==20,candidates
assert candidates[0]["localReceivedQuantity"]==0,candidates
call(route+"/lines/"+str(uuid4())+"/candidates",expected=404)
body={"requestKey":str(uuid4()),"inboundLineId":line,
 "purchaseOrderLineId":poLine,"asnId":asnId,
 "proofReference":"V75 local physical paperwork checked by owner"}
p=post(route,body)
assert p["state"]=="PROPOSED" and p["asnId"]==asnId and p["inboundPlannedQuantity"]==12,p
assert post(route,body)["id"]==p["id"],"idempotent owner proof proposal"
post(route,{**body,"proofReference":"V75 changed identity notes"},expected=409)
post(route,{**body,"requestKey":str(uuid4())},expected=409)
post(route+"/other",{"reason":"not a route"},expected=404)
post(fba+"/"+first+"/dispatch",{},expected=409)
post(route+"/"+p["id"]+"/confirm",{"reason":"V75 independent corroboration"},
 expected=409)
post(route+"/"+p["id"]+"/confirm",{"reason":"V75 reviewed by second independent Owner"},
 browser=second,token=token)
confirmed=call(route)
assert len(confirmed)==1 and confirmed[0]["state"]=="CONFIRMED",confirmed
assert confirmed[0]["confirmedBy"]!=confirmed[0]["proposedBy"],confirmed
assert confirmed[0]["inboundReportedReceived"]==0 and confirmed[0]["localPoReceived"]==0
post(fba+"/"+first+"/dispatch",{})
post(route+"/"+p["id"]+"/revoke",{"reason":"After dispatch cannot change evidence"},expected=409)

# A second draft may use a DIFFERENT portion of this ASN; third may not
# exceed either ASN or approved PO allocation; no inferred cross-shipment receipt.
secondShipment,line2=shipment("V75-FBA-MOCK-002",6)
link2=fba+"/"+secondShipment+"/purchase-links"
part2=post(link2,{**body,"requestKey":str(uuid4()),"inboundLineId":line2,
 "proofReference":"V75 second parcel matching explicit supplier ASN"})
assert part2["state"]=="PROPOSED"
post(link2+"/"+part2["id"]+"/revoke",{"reason":"V75 revoke unconfirmed duplicate candidate"},
 browser=second,token=token)
assert call(link2)[0]["state"]=="REVOKED"
assert post(link2,{**body,"requestKey":str(uuid4()),"inboundLineId":line2,
 "proofReference":"V75 re-propose after auditable withdrawal"})["state"]=="PROPOSED"

third,line3=shipment("V75-FBA-MOCK-003",3)
link3=fba+"/"+third+"/purchase-links"
post(link3,{**body,"requestKey":str(uuid4()),"inboundLineId":line3,
 "proofReference":"V75 excess ASN request must be rejected"},expected=409)

# Confirm on a wrong shipment route must not mutate another shipment's proof.
post(link2+"/"+p["id"]+"/confirm",{"reason":"Wrong shipment proof must be rejected"},
 browser=second,token=token,expected=404)

# Document source values side-by-side; local ASN receipt doesn't mean FBA receipt.
post("/api/v1/purchasing/orders/"+order+"/receive",{
 "lineId":poLine,"quantity":4,"warehouseId":warehouse,"asnId":asnId})
read=call(route)[0]
assert read["asnLocalReceived"]==4 and read["localPoReceived"]==4,read
assert read["inboundReportedReceived"]==0 and read["inboundPlannedQuantity"]==12,read
assert sql(f"""SELECT COUNT(*) FROM fba_inbound_purchase_link_audit
 WHERE tenant_id='{tenant}'""")=="5"
assert sql(f"""SELECT COUNT(*) FROM fba_inbound_purchase_link
 WHERE tenant_id='{tenant}' AND state='CONFIRMED'""")=="1"
assert sql(f"""SELECT COUNT(*) FROM fba_inventory_observation WHERE tenant_id='{tenant}'
 AND seller_marketplace_id='{market}'""")=="0"
print("PASS V75: PG18 scoped ID proof, strict SKU mapping, SHIPPED ASN, split allocation, replay/withdrawal, second Owner confirm, dispatch gate, audit and no FBA stock writes")


# V147 true PostgreSQL 18 HTTP paging: 225 historical revoked links plus
# the existing confirmed link on one shipment. Synthetic SQL creates only
# disposable fixture rows after all V75 lifecycle assertions; production guards
# above prevent these fixture statements from reaching a real tenant.
second_user=call("/api/v1/auth/me",browser=second)["userId"]
sql(f"""INSERT INTO fba_inbound_purchase_link
    (id,tenant_id,shipment_id,inbound_line_id,purchase_order_id,
     purchase_order_line_id,asn_id,request_key,proof_reference,state,
     proposed_by,revoked_by,created_at,revoked_at)
SELECT gen_random_uuid(),'{tenant}'::uuid,'{first}'::uuid,'{line}'::uuid,
       '{order}'::uuid,'{poLine}'::uuid,NULL,gen_random_uuid(),
       'V147 disposable revoked history reference ' || g.n,
       'REVOKED','{me["userId"]}'::uuid,'{second_user}'::uuid,
       now()-INTERVAL '7 days'+g.n*INTERVAL '1 second',now()
FROM generate_series(1,225) AS g(n);""")
assert sql(f"""SELECT COUNT(*) FROM fba_inbound_purchase_link
 WHERE tenant_id='{tenant}' AND shipment_id='{first}'""")=="226"
seen=set();cursor=None;pages=0
while True:
    q={"limit":50}
    if cursor:q["cursor"]=cursor
    data=call(route+"/page?"+urlencode(q))
    assert data["pageSize"]==50 and len(data["items"])<=50,data
    assert data["hasMore"]==bool(data["nextCursor"]),data
    for row in data["items"]:
        assert row["id"] not in seen
        assert row["shipmentId"]==first and row["inboundLineId"]==line
        seen.add(row["id"])
    pages+=1
    cursor=data["nextCursor"]
    if not data["hasMore"]:break
    assert pages<=6,"Pagination must terminate"
assert len(seen)==226 and pages==5,(len(seen),pages)
first_page=call(route+"/page?"+urlencode({"limit":50}))
source_cursor=first_page["nextCursor"]
assert source_cursor
call(route+"/page?"+urlencode({"limit":50,"state":"REVOKED","cursor":source_cursor}),expected=400)
call(route+"/page?"+urlencode({"limit":50,"inboundLineId":str(uuid4()),"cursor":source_cursor}),expected=400)
call(link2+"/page?"+urlencode({"limit":50,"cursor":source_cursor}),expected=400)
call(route+"/page?limit=101",expected=400)
call(route+"/page?state=UNKNOWN",expected=400)
call(route+"/page?cursor=not_valid_%",expected=400)
call(fba+"/"+str(uuid4())+"/purchase-links/page",expected=404)
only_revoked=call(route+"/page?"+urlencode({"limit":50,"state":"REVOKED"}))
assert only_revoked["items"] and all(x["state"]=="REVOKED" for x in only_revoked["items"])

# More than the legacy 100 candidates for the SAME explicitly mapped seller SKU.
# Distinct timestamps and UUID keyset values make every page position reproducible.
sql(f"""INSERT INTO erp_purchase_order
 (id,tenant_id,supplier_id,order_number,currency_code,status,created_by,approved_by,updated_at)
SELECT md5('v147-purchase-order-'||g.n)::uuid,
 '{tenant}'::uuid,'{supplier}'::uuid,
 'V147-PO-'||lpad(g.n::text,3,'0'),'USD','APPROVED',
 '{me["userId"]}'::uuid,'{second_user}'::uuid,now()-g.n*INTERVAL '1 second'
FROM generate_series(1,125) g(n);
INSERT INTO erp_purchase_order_line
 (id,tenant_id,purchase_order_id,product_sku_id,ordered_quantity,unit_cost)
SELECT md5('v147-purchase-line-'||g.n)::uuid,'{tenant}'::uuid,
 md5('v147-purchase-order-'||g.n)::uuid,'{product}'::uuid,10,1.000000
FROM generate_series(1,125) g(n);""")
candidate_route=route+"/lines/"+line+"/candidates/page"
seen_candidate=set();cursor=None;candidate_pages=0
while True:
    q={"limit":50}
    if cursor:q["cursor"]=cursor
    page=call(candidate_route+"?"+urlencode(q))
    assert page["pageSize"]==50 and len(page["items"])<=50,page
    assert page["hasMore"]==bool(page["nextCursor"]),page
    for value in page["items"]:
        key=(value["purchaseOrderLineId"],value["asnId"])
        assert key not in seen_candidate,key
        assert value["asnId"] is None or value["asnStatus"]=="SHIPPED"
        seen_candidate.add(key)
    candidate_pages+=1
    cursor=page["nextCursor"]
    if not page["hasMore"]:break
    assert candidate_pages<=4,"Candidate pages must terminate"
assert len(seen_candidate)==126 and candidate_pages==3,(len(seen_candidate),candidate_pages)
candidate_first=call(candidate_route+"?limit=50")
candidate_cursor=candidate_first["nextCursor"]
assert candidate_cursor
call(candidate_route+"?"+urlencode({"limit":50,"purchaseLineId":poLine,
    "cursor":candidate_cursor}),expected=400)
call(link2+"/lines/"+line2+"/candidates/page?"+urlencode({
    "limit":50,"cursor":candidate_cursor}),expected=400)
call(candidate_route+"?limit=0",expected=400)
call(candidate_route+"?cursor=not_valid_%",expected=400)
exact=call(candidate_route+"?"+urlencode({"purchaseLineId":poLine,"limit":50}))
assert len(exact["items"])==1 and exact["items"][0]["asnId"]==asnId,exact
null_line=sql("SELECT md5('v147-purchase-line-1')::uuid")
no_asn=call(candidate_route+"?"+urlencode({"purchaseLineId":null_line}))
assert len(no_asn["items"])==1 and no_asn["items"][0]["asnId"] is None,no_asn
assert sql(f"""SELECT COUNT(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{market}'""")=="0"
print("PASS V147: PG18 226 link records/5 pages, 126 PO+nullable ASN candidates/3 pages, scope-bound cursors, tenant isolation and no FBA stock mutation")
