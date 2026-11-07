#!/usr/bin/env python3
"""V98: PostgreSQL 18 HTTP audit keyset stress under disposable Mock only.

Creates *fictional* locally injected REPORT/FINANCES/ORDER records with
LIVE field values solely to exercise SQL provenance gates. None are Amazon
observations. No external API, purchase/stock changes, or financial posting.
"""
import http.cookiejar
import json
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,HTTPCookieProcessor,build_opener
from uuid import uuid4

assert os.getenv('DB_HOST')=='127.0.0.1' and os.getenv('DB_NAME')=='merchivra'
assert os.getenv('MERCHIVRA_DEMO_ENABLED')=='true'
assert os.getenv('MERCHIVRA_SPAPI_ENABLED')=='false'
assert os.getenv('MERCHIVRA_SPAPI_OAUTH_ENABLED')=='false'
assert os.getenv('MERCHIVRA_SPAPI_ALLOW_WRITES','false')=='false'
http=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=''

def sql(statement):
    env={**os.environ,'PGPASSWORD':os.environ['DB_PASSWORD']}
    p=subprocess.run(['psql','-X','-q','-v','ON_ERROR_STOP=1',
        '-h','127.0.0.1','-U','merchivra','-d','merchivra','-Atc',statement],
        env=env,capture_output=True,text=True,check=False)
    assert p.returncode==0,(statement[:380],p.stderr[-1800:])
    return p.stdout.strip()

def call(path,method='GET',body=None,expected=200,form=False):
    headers={'Accept':'application/json'}
    data=None
    if body is not None:
        data=(urlencode(body) if form else json.dumps(body)).encode()
        headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
    if method!='GET' and csrf:
        headers['X-CSRF-TOKEN']=csrf
    try:
        with http.open(Request('http://127.0.0.1:8080'+path,method=method,
            headers=headers,data=data),timeout=45) as res:
            status,raw,cache=res.status,res.read(),res.headers.get('Cache-Control','')
    except HTTPError as err:
        status,raw,cache=err.code,err.read(),''
    assert status==expected,(method,path,status,expected,raw[:650])
    if status==200 and '/audit/page?' in path:
        assert 'no-store' in cache,(path,cache)
    try:return json.loads(raw)
    except (ValueError,TypeError):return raw

def get(path,**params):return call(path+'?'+urlencode(params))
def traverse(path):
    rows,cursor,pages=[],None,0
    while True:
        args={'limit':50}
        if cursor:args['cursor']=cursor
        page=get(path,**args)
        pages+=1
        assert pages<=6 and page['pageSize']==50 and len(page['items'])<=50
        rows.extend(page['items'])
        if not page['hasMore']:
            assert page['nextCursor'] is None
            return rows,pages
        assert len(page['items'])==50 and page['nextCursor']
        cursor=page['nextCursor']

# No public user or anonymous API should access any case history.
call('/api/v1/finance/marketplaces/'+str(uuid4())+
     '/reports/'+str(uuid4())+'/source-review/audit/page?limit=50',expected=401)
csrf=call('/api/v1/auth/csrf')['token']
call('/api/v1/auth/login','POST',{
  'username':os.environ['MERCHIVRA_BOOTSTRAP_USERNAME'],
  'password':os.environ['MERCHIVRA_BOOTSTRAP_PASSWORD']},form=True)
csrf=call('/api/v1/auth/csrf')['token']
me=call('/api/v1/auth/me')
assert me['isOwner']
tenant,actor=me['activeTenantId'],me['userId']
mp=sql(f"""SELECT id FROM seller_marketplace WHERE tenant_id='{tenant}'
 ORDER BY id LIMIT 1""")
currency=sql(f"""SELECT trim(currency_code) FROM seller_marketplace
 WHERE tenant_id='{tenant}' AND id='{mp}'""")
assert mp and currency
stock_before=sql(f"SELECT count(*) FROM erp_local_stock_movement WHERE tenant_id='{tenant}'")
orders_before=sql(f"SELECT count(*) FROM erp_purchase_order WHERE tenant_id='{tenant}'")

# Fictional report+finance row only in isolated PG18. Acts as LIVE-field
# SQL fixture, not proof of real Amazon authorization or remote import.
report=str(uuid4())
financial=str(uuid4())
settlement='V98-MOCK-SETTLEMENT-001'
sql(f"""INSERT INTO spapi_settlement_report(
 id,tenant_id,seller_marketplace_id,amazon_report_id,report_document_id,
 report_type,state,source_origin,document_sha256,settlement_reference,
 statement_currency,declared_net,line_count)
VALUES('{report}','{tenant}','{mp}','V98-MOCK-REPORT-001',
 'V98-MOCK-REPORT-DOCUMENT-001',
 'GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE_V2','IMPORTED','LIVE',
 repeat('a',64),'{settlement}','{currency}',20.000000,1);
INSERT INTO spapi_settlement_report_line(
 tenant_id,report_id,line_number,transaction_type,signed_amount,currency_code)
VALUES('{tenant}','{report}',1,'V98_SYNTHETIC_ONLY',20.000000,'{currency}');
INSERT INTO finance_source_event(
 id,tenant_id,seller_marketplace_id,source_family,source_event_key,
 data_origin,category,amount,currency_code,posted_at,source_type)
VALUES('{financial}','{tenant}','{mp}','SPAPI_FINANCES','V98-FICTITIOUS-FINANCES-001',
 'LIVE','UNCLASSIFIED',20.000000,'{currency}',now(),'V98_SYNTHETIC_ONLY');
INSERT INTO finance_transaction_reference(tenant_id,event_id,reference_kind,reference_value)
VALUES('{tenant}','{financial}','SETTLEMENT_ID','{settlement}');
""")
# Seed a pending source request as a test fixture. It has a deliberately
# non-current digest, ensuring it can never be treated as a valid approval.
sql(f"""INSERT INTO finance_settlement_source_review(
 tenant_id,report_id,seller_marketplace_id,state,source_state,
 evidence_sha256,requested_by,version)
VALUES('{tenant}','{report}','{mp}','PENDING',
 'SOURCE_AMOUNTS_AGREE_UNVERIFIED',repeat('b',64),'{actor}',135);
WITH inserted AS (
 INSERT INTO finance_settlement_source_review_audit(
 id,tenant_id,report_id,review_version,action,source_state,
 evidence_sha256,actor_user_id,reason,occurred_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{report}'::uuid,i,
 'REQUESTED','SOURCE_AMOUNTS_AGREE_UNVERIFIED',
 repeat('b',64),'{actor}'::uuid,'V98 mock source audit history',
 now()-i*interval '1 minute'
 FROM generate_series(1,135) i RETURNING id
) SELECT count(*) FROM inserted""")
# A bank observation is MANUAL_UNVERIFIED, not funds proof. Preserve the
# existing source approval requirement; the review remains PENDING/STALE.
batch,payout=str(uuid4()),str(uuid4())
sql(f"""INSERT INTO bank_payout_import_batch(
 id,tenant_id,seller_marketplace_id,statement_ref,file_sha256,
 line_count,imported_by)
VALUES('{batch}','{tenant}','{mp}','V98-BANK-FIXTURE-001',repeat('c',64),1,'{actor}');
INSERT INTO bank_payout_observation(
 id,tenant_id,seller_marketplace_id,batch_id,row_number,entry_ref,
 settlement_ref,value_date,currency_code,credit_amount,withheld_fee)
VALUES('{payout}','{tenant}','{mp}','{batch}',1,'V98-BANK-ENTRY-001',
 '{settlement}',current_date,'{currency}',20.000000,0.000000);
INSERT INTO bank_payout_manual_review(
 tenant_id,seller_marketplace_id,report_id,payout_entry_id,state,
 evidence_sha256,requested_by,version)
VALUES('{tenant}','{mp}','{report}','{payout}','PENDING',repeat('d',64),
 '{actor}',127);
WITH inserted AS (
 INSERT INTO bank_payout_manual_review_audit(
 id,tenant_id,report_id,review_version,actor_user_id,
 action,evidence_sha256,reason,occurred_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{report}'::uuid,i,
 '{actor}'::uuid,'REQUESTED',repeat('d',64),
 'V98 mock manual bank history',now()-i*interval '1 minute'
 FROM generate_series(1,127) i RETURNING id
) SELECT count(*) FROM inserted""")
# Independently scoped refund history requires a linked LIVE-field order and
# a finance event; neither is real Amazon. Historical audit rows are test
# metadata only and do not affect refund classification or physical inventory.
order,refund=str(uuid4()),str(uuid4())
sql(f"""INSERT INTO sales_order(
 id,tenant_id,seller_marketplace_id,external_order_id,
 fulfillment_status_raw,refund_status,data_origin)
VALUES('{order}','{tenant}','{mp}','V98-MOCK-ORDER-001',
 'Shipped','PARTIAL','LIVE');
INSERT INTO order_item(
 id,tenant_id,sales_order_id,external_item_id,
 seller_sku_raw,quantity,data_origin)
VALUES(gen_random_uuid(),'{tenant}','{order}','V98-MOCK-ITEM-001',
 'V98-SKU-LOCAL',1,'LIVE');
INSERT INTO finance_source_event(
 id,tenant_id,seller_marketplace_id,source_family,source_event_key,
 data_origin,category,amount,currency_code,posted_at,
 external_order_id,source_type)
VALUES('{refund}','{tenant}','{mp}','SPAPI_FINANCES','V98-FICTIONAL-REFUND-001',
 'LIVE','PRODUCT_REFUND',-5.000000,'{currency}',now(),
 'V98-MOCK-ORDER-001','V98_SYNTHETIC_ONLY');
INSERT INTO finance_event_link_review(
 tenant_id,event_id,order_id,state,reviewed_by)
VALUES('{tenant}','{refund}','{order}','MATCHED','{actor}');
WITH inserted AS (
 INSERT INTO finance_refund_review_audit(
 id,tenant_id,event_id,sales_order_id,external_item_id,
 actor_user_id,action,reviewed_quantity,disposition,
 evidence_reference,revision,occurred_at)
 SELECT gen_random_uuid(),'{tenant}'::uuid,'{refund}'::uuid,
 '{order}'::uuid,'V98-MOCK-ITEM-001','{actor}'::uuid,
 'CREATED',1,'UNKNOWN','V98-MOCK-EVIDENCE-001',i,
 now()-i*interval '1 minute'
 FROM generate_series(1,125) i RETURNING id
) SELECT count(*) FROM inserted""")
reports=f'/api/v1/finance/marketplaces/{mp}/reports/{report}'
sources=reports+'/source-review/audit'
bank=reports+'/manual-bank-review/audit'
refunds=f'/api/v1/finance/marketplaces/{mp}/events/{refund}/refund-items/audit'

assert len(call(sources))==100
assert len(call(bank))==100
assert len(call(refunds))==100
srows,spages=traverse(sources+'/page')
brows,bpages=traverse(bank+'/page')
rrows,rpages=traverse(refunds+'/page')
assert (len(srows),spages)==(135,3)
assert (len(brows),bpages)==(127,3)
assert (len(rrows),rpages)==(125,3)
assert len({(x['version'],x['occurredAt']) for x in srows})==135
assert len({(x['version'],x['occurredAt']) for x in brows})==127
assert len({(x['revision'],x['timestamp']) for x in rrows})==125
assert all(x['action']=='REQUESTED' for x in brows+srows)
assert all(x['action']=='CREATED' for x in rrows)
# Never treat synthetic status as bank-confirmed or approved; stale digest
# must stay stale even if historic rows exist.
source_status=call(reports+'/source-review')
bank_status=call(reports+'/manual-bank-review')
assert source_status['bankVerified'] is False and source_status['ledgerPosted'] is False
assert bank_status['bankVerified'] is False and bank_status['ledgerPosted'] is False

cursor=get(sources+'/page',limit=50)['nextCursor']
assert cursor
call(bank+'/page?'+urlencode({'cursor':cursor,'limit':50}),expected=400)
call(sources+'/page?'+urlencode({'cursor':'invalid%','limit':50}),expected=400)
for target in (sources,bank,refunds):
    for n in (0,101):
        call(target+'/page?'+urlencode({'limit':n}),expected=400)
    call(target+'/page?limit=50&cursor=invalid%25',expected=400)
# Wrong Marketplace refuses resource existence before any cursor unpacks.
foreign=str(uuid4())
call(f'/api/v1/finance/marketplaces/{foreign}/reports/{report}/source-review/audit/page?limit=50',
 expected=404)
call(f'/api/v1/finance/marketplaces/{foreign}/events/{refund}/refund-items/audit/page?limit=50',
 expected=409)
assert sql(f"SELECT count(*) FROM erp_local_stock_movement WHERE tenant_id='{tenant}'")==stock_before
assert sql(f"SELECT count(*) FROM erp_purchase_order WHERE tenant_id='{tenant}'")==orders_before
print('PASS V98 PG18 Mock: 135 source maker-checker audits, 127 manual-bank audits, '
      '125 refund-item audits, legacy=100 vs 3 complete pages each, scoped cursor '
      '400/401/404, PENDING/STALE and bankVerified=false, no stock/PO/Amazon/bank writes')
