#!/usr/bin/env python3
"""V92: all local FBA shipment history (125 across 50+50+25) and 130th
internal SKU supplier rules. Disposable localhost PG18 only, no Amazon.
"""
import http.cookiejar,json,os,subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,HTTPCookieProcessor,build_opener
from uuid import uuid4

assert os.getenv('DB_HOST')=='127.0.0.1' and os.getenv('DB_NAME')=='merchivra'
assert os.getenv('MERCHIVRA_DEMO_ENABLED')=='true'
assert os.getenv('MERCHIVRA_SPAPI_ENABLED')=='false'
assert os.getenv('MERCHIVRA_SPAPI_OAUTH_ENABLED')=='false'
http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=''
def sql(q):
 env={**os.environ,'PGPASSWORD':os.environ['DB_PASSWORD']}
 r=subprocess.run(['psql','-X','-q','-v','ON_ERROR_STOP=1',
   '-h','127.0.0.1','-U','merchivra','-d','merchivra','-Atc',q],
   env=env,text=True,capture_output=True,check=False)
 assert r.returncode==0,r.stderr[-1400:]
 return r.stdout.strip()
def call(path,method='GET',body=None,expected=200,form=False):
 headers={'Accept':'application/json'}
 data=None
 if body is not None:
  data=(urlencode(body) if form else json.dumps(body)).encode()
  headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
 if method!='GET' and csrf:headers['X-CSRF-TOKEN']=csrf
 try:
  with http.open(Request('http://127.0.0.1:8080'+path,
       method=method,headers=headers,data=data),timeout=30) as r:
   status,raw=r.status,r.read();cache=r.headers.get('Cache-Control','')
 except HTTPError as e:
  status,raw=e.code,e.read();cache=''
 assert status==expected,(method,path,status,expected,raw[:900])
 if status==200:assert 'no-store' in cache,(path,cache)
 try:return json.loads(raw)
 except (ValueError,TypeError):return raw
def get(base,**kwargs):return call(base+'?'+urlencode(kwargs))
foreign=str(uuid4())
call(f'/api/v1/fba/marketplaces/{foreign}/shipments/page',expected=401)
csrf=call('/api/v1/auth/csrf')['token']
call('/api/v1/auth/login','POST',{
 'username':os.environ['MERCHIVRA_BOOTSTRAP_USERNAME'],
 'password':os.environ['MERCHIVRA_BOOTSTRAP_PASSWORD']},form=True)
csrf=call('/api/v1/auth/csrf')['token']
me=call('/api/v1/auth/me')
assert me['isOwner']
tenant=me['activeTenantId']
actor=me['userId']
mp=sql(f"""SELECT seller_marketplace_id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND reference='V76-IDENTITY-PENDING'""")
assert mp
base=f'/api/v1/fba/marketplaces/{mp}/shipments/page'
call(f'/api/v1/fba/marketplaces/{foreign}/shipments/page',expected=404)
for invalid in ({'limit':0},{'limit':101},{'search':'X'*81},
                {'search':'A\nB'},{'state':'LIVE'},{'cursor':'not@cursor'}):
 call(base+'?'+urlencode(invalid),expected=400)
assert sql("""SELECT COUNT(*) FROM pg_indexes
 WHERE tablename='fba_inbound_shipment' AND
 indexname IN ('fba_inbound_shipment_v81_recent_keyset',
               'fba_inbound_shipment_v81_reference_prefix')""")=='2'
fba_before=int(sql(f"""SELECT COUNT(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'"""))
ledger_before=int(sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'"""))
po_before=int(sql(f"""SELECT COUNT(*) FROM erp_purchase_order
 WHERE tenant_id='{tenant}'"""))
# Generate only local Mock history; second row IN_TRANSIT must have a
# dispatched_at timestamp, and all other rows remain unconfirmed DRAFT.
sql(f"""INSERT INTO fba_inbound_shipment
 (id,tenant_id,seller_marketplace_id,request_key,request_sha256,
  reference,evidence_origin,state,created_by,created_at,dispatched_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{mp}'::uuid,
   gen_random_uuid(),repeat('a',64),
   'V92-LOCAL-'||lpad(i::text,3,'0'),'MOCK',
   CASE WHEN i=2 THEN 'IN_TRANSIT' ELSE 'DRAFT' END,
   '{actor}'::uuid,
   timestamptz '2026-11-04T06:00:00Z'-(i||' minutes')::interval,
   CASE WHEN i=2 THEN
     timestamptz '2026-11-04T06:00:00Z'-(i||' minutes')::interval
     ELSE NULL END
 FROM generate_series(1,125) i""")
oldest=sql(f"""SELECT id FROM fba_inbound_shipment
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'
 AND reference='V92-LOCAL-125'""")
assert oldest
sql(f"""INSERT INTO fba_inbound_line
 (id,tenant_id,shipment_id,seller_sku,planned_quantity)
 VALUES(gen_random_uuid(),'{tenant}','{oldest}','V87-PAGED-064',7)""")
first=get(base,limit=50,search='v92-local-',state='ALL')
assert first['pageSize']==50 and first['searchPrefix']=='v92-local-'
assert first['state']=='ALL' and first['hasMore']
assert len(first['items'])==50 and first['nextCursor']
second=get(base,limit=50,search='V92-LOCAL-',state='ALL',cursor=first['nextCursor'])
assert len(second['items'])==50 and second['hasMore']
third=get(base,limit=50,search='V92-LOCAL-',state='ALL',cursor=second['nextCursor'])
assert len(third['items'])==25 and not third['hasMore']
assert third['nextCursor'] is None
all_rows=first['items']+second['items']+third['items']
assert len({r['id'] for r in all_rows})==125
assert all_rows[0]['reference']=='V92-LOCAL-001'
assert all_rows[-1]['reference']=='V92-LOCAL-125'
assert all_rows[-1]['id']==oldest
assert all(r['origin']=='MOCK' for r in all_rows)
assert all_rows[1]['state']=='IN_TRANSIT'
assert all_rows[1]['dispatchedAt']
detail=call(f'/api/v1/fba/marketplaces/{mp}/shipments/{oldest}')
assert detail['shipment']['reference']=='V92-LOCAL-125'
assert detail['lines'][0]['sellerSku']=='V87-PAGED-064'
assert detail['lines'][0]['plannedQuantity']==7
assert detail['amazonVerified'] is False
assert detail['fbaStockCredited'] is False
transit=get(base,search='V92-LOCAL-',state='IN_TRANSIT')
assert len(transit['items'])==1
assert transit['items'][0]['reference']=='V92-LOCAL-002'
draft1=get(base,search='V92-LOCAL-',state='DRAFT',limit=50)
draft2=get(base,search='V92-LOCAL-',state='DRAFT',limit=50,
            cursor=draft1['nextCursor'])
draft3=get(base,search='V92-LOCAL-',state='DRAFT',limit=50,
            cursor=draft2['nextCursor'])
assert len(draft1['items'])==50 and len(draft2['items'])==50
assert len(draft3['items'])==24 and not draft3['hasMore']
for args in ({'state':'DRAFT'},{'search':'V92-LOCAL-0'},
             {'limit':50,'search':'V92-LOCAL-', 'state':'RECEIVING'}):
 kw={'search':'V92-LOCAL-','state':'ALL','cursor':first['nextCursor'],**args}
 call(base+'?'+urlencode(kw),expected=400)
call(f'/api/v1/fba/marketplaces/{foreign}/shipments/page?'+
     urlencode({'search':'V92-LOCAL-','cursor':first['nextCursor']}),expected=404)
# Real SQL literal-prefix semantics: percent/underscore/exclamation are NOT LIKE wildcards.
for i,name in enumerate(('V92%PERCENT','V92_UNDERSCORE','V92!BANG')):
 sql(f"""INSERT INTO fba_inbound_shipment(
 id,tenant_id,seller_marketplace_id,request_key,request_sha256,reference,
 evidence_origin,created_by,created_at)
 VALUES(gen_random_uuid(),'{tenant}','{mp}',gen_random_uuid(),
 repeat('b',64),'{name}','MOCK','{actor}',
 timestamptz '2026-11-04T03:00:00Z')""")
 result=get(base,search=name)
 assert len(result['items'])==1 and result['items'][0]['reference']==name
assert len(get(base,search='V92%LOCAL')['items'])==0
assert len(get(base,search='V92_LOCAL')['items'])==0
# Old V65 list is untouched, intentionally most recent <=100 only. New one is
# explicitly all and does not claim frozen ledger semantics.
old=call(f'/api/v1/fba/marketplaces/{mp}/shipments')
assert isinstance(old,list) and len(old)<=100

# Supplier rule editor for SKU #130 (outside the old first 100 catalog rows),
# entirely through existing Owner-only SupplierPurchasePlanController.
sku=sql(f"""SELECT id FROM product_sku
 WHERE tenant_id='{tenant}' AND internal_sku='V91-INTERNAL-130'""")
supplier=sql(f"""SELECT id FROM erp_supplier WHERE tenant_id='{tenant}'
 AND supplier_code='V91SUPPLIER'""")
assert sku and supplier
rules='/api/v1/purchasing/planning/supplier-rules'
body={'supplierId':supplier,'productSkuId':sku,
 'minimumOrderQuantity':8,'packMultiple':4,'leadTimeDays':18,
 'currency':'USD','unitCost':'2.500000'}
created=call(rules,'POST',body)
assert created['productSkuId']==sku and created['supplierId']==supplier
assert created['minimumOrderQuantity']==8 and created['packMultiple']==4
body['minimumOrderQuantity']=12
updated=call(rules,'POST',body)
assert updated['minimumOrderQuantity']==12
assert sum(1 for rule in call(rules)
           if rule['supplierId']==supplier and rule['productSkuId']==sku)==1
assert sql(f"""SELECT count(*) FROM erp_supplier_sku_rule
 WHERE tenant_id='{tenant}' AND supplier_id='{supplier}'
 AND product_sku_id='{sku}'""")=='1'
assert sql(f"""SELECT count(*) FROM erp_supplier_sku_rule_audit
 WHERE tenant_id='{tenant}' AND supplier_id='{supplier}'
 AND product_sku_id='{sku}' AND event_type='RULE_SET'""")=='2'

assert int(sql(f"""SELECT COUNT(*) FROM erp_purchase_order
 WHERE tenant_id='{tenant}'"""))==po_before
assert int(sql(f"""SELECT COUNT(*) FROM fba_inventory_observation
 WHERE tenant_id='{tenant}' AND seller_marketplace_id='{mp}'"""))==fba_before
assert int(sql(f"""SELECT COUNT(*) FROM erp_local_stock_movement
 WHERE tenant_id='{tenant}'"""))==ledger_before
print('PASS V92: PG18 125 local shipments 50+50+25, original detail, status,'
      ' literal special-character prefix, cursor scope/400/401/404;'
      ' 130th SKU supplier rule create/edit, no PO/FBA/warehouse/Amazon writes')
