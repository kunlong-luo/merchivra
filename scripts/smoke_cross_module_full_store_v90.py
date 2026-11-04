#!/usr/bin/env python3
"""V90 cross-module full-store choice: late Listing -> replenishment policy,
late Order -> finance manual review. Disposable PG18 HTTP only. No Amazon.
"""
import http.cookiejar,json,os,subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,HTTPCookieProcessor,build_opener
from uuid import uuid4
from datetime import datetime,timezone

assert os.getenv("DB_HOST")=="127.0.0.1" and os.getenv("DB_NAME")=="merchivra"
assert os.getenv("MERCHIVRA_SPAPI_ENABLED")=="false"
assert os.getenv("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
assert os.getenv("MERCHIVRA_DEMO_ENABLED")=="true"

http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=""
def sql(statement):
    env={**os.environ,"PGPASSWORD":os.environ["DB_PASSWORD"]}
    r=subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1","-h","127.0.0.1",
      "-U","merchivra","-d","merchivra","-Atc",statement],
      capture_output=True,text=True,env=env,check=False)
    assert r.returncode==0,r.stderr[-1600:]
    return r.stdout.strip()
def call(path,method="GET",body=None,expected=200,form=False):
    headers={"Accept":"application/json"}
    payload=None
    if body is not None:
        payload=(urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"]="application/x-www-form-urlencoded" if form else "application/json"
    if method!="GET" and csrf:headers["X-CSRF-TOKEN"]=csrf
    try:
        with http.open(Request("http://127.0.0.1:8080"+path,method=method,
                  headers=headers,data=payload),timeout=30) as r:
            status,raw=r.status,r.read()
            cache=r.headers.get("Cache-Control","")
    except HTTPError as ex:
        status,raw=ex.code,ex.read();cache=""
    assert status==expected,(method,path,status,expected,raw[:800])
    try:return json.loads(raw)
    except (ValueError,TypeError):return raw
def page(path,**kwargs):return call(path+"?"+urlencode(kwargs))
foreign=str(uuid4())
call(f"/api/v1/seller-marketplaces/{foreign}/orders/page",expected=401)
csrf=call("/api/v1/auth/csrf")["token"]
call("/api/v1/auth/login","POST",{
 "username":os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],
 "password":os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]},form=True)
csrf=call("/api/v1/auth/csrf")["token"]
me=call("/api/v1/auth/me")
assert me["isOwner"]
tenant=me["activeTenantId"]
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp
site=next(m for m in call("/api/v1/seller-marketplaces") if m["id"]==mp)
currency=site["currency"]
listings=f"/api/v1/seller-marketplaces/{mp}/listings/page"
orders=f"/api/v1/seller-marketplaces/{mp}/orders/page"
repl=f"/api/v1/replenishment/marketplaces/{mp}"
overview_before=call("/api/v1/business-overview")
initial_site=next(m for m in overview_before["marketplaces"] if m["id"]==mp)
assert initial_site["listings"]["total"]>=64
assert initial_site["orders"]["total"]>=64

# Frontend picker 50+14 page flow must find SKU beyond initial listing choices.
first=page(listings,field="SELLER_SKU",search="V87-PAGED-",status="ALL",limit=50)
assert first["hasMore"] and len(first["items"])==50
assert "V87-PAGED-064" not in {x["sellerSku"] for x in first["items"]}
tail=page(listings,field="SELLER_SKU",search="V87-PAGED-",status="ALL",
          limit=50,cursor=first["nextCursor"])
assert len(tail["items"])==14 and not tail["hasMore"]
listing=next(x for x in tail["items"] if x["sellerSku"]=="V87-PAGED-064")
assert listing["mappingStatus"]=="CONFLICT" and listing["dataOrigin"]=="MOCK"
exact=page(listings,field="SELLER_SKU",search="V87-PAGED-064",status="ALL",limit=50)
assert len(exact["items"])==1 and exact["items"][0]["id"]==listing["id"]
call(f"/api/v1/seller-marketplaces/{foreign}/listings/page",expected=404)

# V70 configured policy is a human-local operation; it must not produce
# a DRAFT PO, FBA source observation or platform change automatically.
po_before=int(sql(f"""SELECT COUNT(*) FROM erp_purchase_order WHERE tenant_id='{tenant}'"""))
fba_before=int(sql(f"""SELECT COUNT(*) FROM fba_inventory_observation WHERE
 tenant_id='{tenant}' AND seller_marketplace_id='{mp}'"""))
policy=call(repl+"/policies","POST",{
 "sellerSku":listing["sellerSku"],"reorderPoint":3,
 "targetStock":20,"leadTimeDays":14})
assert policy["sellerSku"]=="V87-PAGED-064",policy
advice=next(x for x in call(repl+"/suggestions")
            if x["policy"]["sellerSku"]=="V87-PAGED-064")
assert advice["state"] in ("NO_OBSERVATION","STALE_OBSERVATION",
                          "INCOMPLETE_OBSERVATION")
assert advice["suggestedQuantity"] is None
assert int(sql(f"SELECT COUNT(*) FROM erp_purchase_order WHERE tenant_id='{tenant}'"))==po_before
assert int(sql(f"""SELECT COUNT(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'"""))==fba_before

# V86 original source-updated-time ordering is DIFFERENT from Listing
# alphabetical order. Both support full-store prefix and opaque keyset.
first_orders=page(orders,search="V86-PAGED-",limit=50)
assert len(first_orders["items"])==50 and first_orders["hasMore"]
assert "V86-PAGED-064" not in {x["externalOrderId"] for x in first_orders["items"]}
last_orders=page(orders,search="V86-PAGED-",limit=50,
                 cursor=first_orders["nextCursor"])
assert len(last_orders["items"])==14 and not last_orders["hasMore"]
target=next(x for x in last_orders["items"]
            if x["externalOrderId"]=="V86-PAGED-064")
assert target["itemsObservation"]=="NOT_OBSERVED"
assert target["dataOrigin"]=="MOCK"
assert target["financialStatus"]=="NOT_OBSERVED"
exact=page(orders,search="V86-PAGED-064",limit=50)
assert len(exact["items"])==1 and exact["items"][0]["id"]==target["id"]
# Cursor from another Marketplace must not become an authorization token.
call(f"/api/v1/seller-marketplaces/{foreign}/orders/page?"+
     urlencode({"cursor":first_orders["nextCursor"]}),expected=404)

# Finance manual event binds the 64th matching order, not whichever was
# visible on the first 50 rows. Do not imply source evidence is LIVE.
event=call("/api/v1/finance/manual-events","POST",{
 "marketplaceId":mp,"eventKey":"V90-CROSS-ORDER-"+str(uuid4()),
 "category":"PRODUCT_SALES","amount":"12.340000","currency":currency,
 "postedAt":datetime.now(timezone.utc).isoformat(),
 "externalOrderId":target["externalOrderId"],
 "sourceType":"V90_TEST_MANUAL"},expected=201)
assert event["sourceFamily"]=="MANUAL"
assert event["dataOrigin"]=="MANUAL_UNVERIFIED"
assert event["reviewStatus"]=="REVIEW_REQUIRED"
# A different site's order, even in the same tenant, is forbidden.
another=sql(f"""SELECT id FROM sales_order
 WHERE tenant_id='{tenant}' AND seller_marketplace_id<>'{mp}'
 ORDER BY id LIMIT 1""")
assert another
call(f"/api/v1/finance/events/{event['id']}/review","POST",
     {"marketplaceId":mp,"orderId":another},expected=404)
linked=call(f"/api/v1/finance/events/{event['id']}/review","POST",
     {"marketplaceId":mp,"orderId":target["id"]})
assert linked["state"]=="MATCHED" and linked["orderId"]==target["id"]
assert sql(f"""SELECT order_id FROM finance_event_link_review
 WHERE tenant_id='{tenant}' AND event_id='{event['id']}'""")==target["id"]
events=call(f"/api/v1/finance/marketplaces/{mp}/events?limit=50")
reviewed=next(x for x in events if x["id"]==event["id"])
assert reviewed["reviewStatus"]=="MATCHED"
assert reviewed["reviewedOrderId"]==target["id"]
assert reviewed["dataOrigin"]=="MANUAL_UNVERIFIED"
assert reviewed["externalOrderId"]==target["externalOrderId"]
assert sql(f"""SELECT financial_status FROM sales_order
 WHERE tenant_id='{tenant}' AND id='{target['id']}'""")=="NOT_OBSERVED"
# V64 contribution and marketplace integrity remain independent of a
# manually reviewed event.
overview_after=call("/api/v1/business-overview")
final_site=next(m for m in overview_after["marketplaces"] if m["id"]==mp)
assert final_site["listings"]["total"]==initial_site["listings"]["total"]
assert final_site["orders"]["total"]==initial_site["orders"]["total"]
assert final_site["inventory"]["skus"]==initial_site["inventory"]["skus"]
assert not overview_after["crossCurrencyTotalsAvailable"]

print("PASS V90: late 64th Listing→replenishment policy (no automatic PO),"
      " late 64th Order→manual finance review with exact scoped UUID,"
      " separate MOCK vs MANUAL source, 401/404, no FBA/Amazon mutation")
