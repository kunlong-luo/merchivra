import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchSupplierSkuRulesPage,fetchLocalPurchaseRecommendationsPage,
 fetchLocalPurchasePlansPage,removeSupplierSkuRule,setSupplierSkuRule,
 proposeLocalPurchasePlan} from './purchase-planning'
import {fetchPurchaseVariancesPage,fetchSupplierClaimsPage,
 fetchSupplierClaimSourcesPage,fetchSupplierCaseEventsPage,
 actSupplierClaim} from './supplier-disputes'
import {submitDispositionProof,decideDispositionProof} from './lot-disposition-proofs'

const id=(n:number)=>'00000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')
const warehouse=id(1),supplier=id(2),sku=id(3),order=id(4),variance=id(5)
const claimId=id(6),lotId=id(7),requestKey=id(8),ruleAt='2026-11-09T08:00:00Z'
const rule={supplierId:supplier,productSkuId:sku,minimumOrderQuantity:20,
 packMultiple:12,leadTimeDays:14,currency:'USD',unitCost:'2.500001',updatedAt:ruleAt}
const claim={id:claimId,orderId:order,supplierId:supplier,varianceId:variance,
 inspectionId:null,requestedQuantity:3,remedy:'REPLACEMENT',
 reason:'Three boxes missing in actual receipt',status:'CLOSED',
 createdBy:id(9),approvedBy:id(10),resolutionNote:'Supplier response was documented',
 createdAt:ruleAt}
const proof={id:id(11),dispositionId:lotId,proofKind:'DISPOSAL_CERTIFICATE',
 evidenceReference:'DIS-2026-REAL',documentSha256:'a'.repeat(64),
 explanation:'Internal submitted evidence metadata',
 submittedBy:id(9),status:'PENDING_REVIEW',reviewedBy:null,reviewReason:null}
const emptyPage={items:[],hasMore:false,nextCursor:null,pageSize:50}
type Sent={url:string;init:RequestInit}
afterEach(()=>vi.unstubAllGlobals())
function mock(handler:(url:string,init:RequestInit)=>{status?:number;payload?:unknown}){
 const seen:Sent[]=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,init:RequestInit={})=>{
  if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
   token:'v118-valid-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
  seen.push({url,init})
  const {status=200,payload=emptyPage}=handler(url,init)
  if(status===204)return new Response(null,{status:204})
  return new Response(JSON.stringify(payload),{status})
 }))
 return seen
}
function assertTrusted(sent:Sent){
 expect(sent.init.credentials).toBe('same-origin')
 expect(sent.init.cache).toBe('no-store')
 expect(sent.url).not.toContain('tenantId=')
}
describe('V118 generated procurement terms and source-bound supplier cases',()=>{
 it('all existing planning keyset pages forward the full 50-page cursor',async()=>{
  const sent=mock(()=>({payload:emptyPage}))
  const pages=[
   await fetchSupplierSkuRulesPage('KEY1'),
   await fetchLocalPurchaseRecommendationsPage(warehouse,'KEY2'),
   await fetchLocalPurchasePlansPage(warehouse,'KEY3')
  ]
  expect(pages.map(p=>p.hasMore)).toEqual([false,false,false])
  expect(sent).toHaveLength(3)
  expect(sent.map(x=>new URL(x.url,'http://localhost').searchParams.get('cursor')))
   .toEqual(['KEY1','KEY2','KEY3'])
  for(const req of sent){
   assertTrusted(req)
   expect(req.init.method).toBe('GET')
   expect(new URL(req.url,'http://localhost').searchParams.get('limit')).toBe('50')
  }
 })
 it('preserves exact supplier price decimal and CSRF; DELETE is 204 without fake JSON',async()=>{
  const sent=mock((url,init)=>({
   status:init.method==='DELETE'?204:200,payload:rule
  }))
  const saved=await setSupplierSkuRule(supplier,sku,20,12,14,'USD','2.500001')
  expect(saved.unitCost).toBe('2.500001')
  expect(JSON.parse(String(sent[0].init.body))).toEqual({
   supplierId:supplier,productSkuId:sku,minimumOrderQuantity:20,
   packMultiple:12,leadTimeDays:14,currency:'USD',unitCost:'2.500001'
  })
  expect(new Headers(sent[0].init.headers).get('X-CSRF-TOKEN'))
   .toBe('v118-valid-csrf-token')
  const result=await removeSupplierSkuRule(supplier,sku)
  expect(result).toBeUndefined()
  expect(sent[1].init.method).toBe('DELETE')
  expect(sent[1].url).toBe('/api/v1/purchasing/planning/supplier-rules/'+supplier+'/'+sku)
  expect(new Headers(sent[1].init.headers).get('X-CSRF-TOKEN'))
   .toBe('v118-valid-csrf-token')
 })
 it('never converts unknown on-hand into a zero-stock PO proposal',async()=>{
  const rec={warehouseId:warehouse,supplierId:supplier,supplierName:'Vendor A',
   productSkuId:sku,internalSku:'SKU-X',sourceMovementId:null,
   policyUpdatedAt:ruleAt,ruleUpdatedAt:ruleAt,onHandQuantity:null,
   reorderPoint:10,targetStock:20,minimumOrderQuantity:20,packMultiple:12,
   leadTimeDays:14,currency:'USD',unitCost:'2.50',state:'NO_LEDGER',
   suggestedQuantity:null,orderQuantity:null}
  const sent=mock(()=>({payload:[rec]}))
  const result=await import('./purchase-planning').then(m=>
   m.fetchLocalPurchaseRecommendations(warehouse))
  expect(result[0].onHandQuantity).toBeNull()
  expect(result[0].suggestedQuantity).toBeNull()
  expect(sent[0].init.method).toBe('GET')
 })
 it('status/filter/cursor for variance, claim, available source and audit are server bound',async()=>{
  const sent=mock(()=>({payload:emptyPage}))
  await fetchPurchaseVariancesPage(order,'ALL','V1')
  await fetchSupplierClaimsPage(order,'ACTIONABLE','C2')
  await fetchSupplierClaimSourcesPage(order,'S3')
  await fetchSupplierCaseEventsPage(variance,'variances','E4')
  await fetchSupplierCaseEventsPage(claimId,'claims','E5')
  expect(sent).toHaveLength(5)
  expect(sent.map(x=>new URL(x.url,'http://localhost').searchParams.get('cursor')))
   .toEqual(['V1','C2','S3','E4','E5'])
  expect(new URL(sent[0].url,'http://localhost').searchParams.get('filter')).toBe('ALL')
  expect(new URL(sent[1].url,'http://localhost').searchParams.get('filter'))
   .toBe('ACTIONABLE')
  for(const req of sent){assertTrusted(req);expect(req.init.method).toBe('GET')}
 })
 it('claim closure sends explicit review note; generated client never implies external credit',async()=>{
  const sent=mock(()=>({payload:claim}))
  const closed=await actSupplierClaim(claimId,'close','Supplier response was documented')
  expect(closed.status).toBe('CLOSED')
  expect(sent[0].url).toBe('/api/v1/purchasing/disputes/claims/'+claimId+'/close')
  expect(sent[0].init.method).toBe('POST')
  expect(JSON.parse(String(sent[0].init.body))).toEqual({
   note:'Supplier response was documented'
  })
  expect(new Headers(sent[0].init.headers).get('X-CSRF-TOKEN'))
   .toBe('v118-valid-csrf-token')
 })
 it('metadata evidence hash is not an uploaded file or external authentication',async()=>{
  const sent=mock((url)=>({payload:
   url.endsWith('/decision')?{...proof,status:'METADATA_REVIEWED',
    reviewedBy:id(10),reviewReason:'Internal metadata validated'}:proof}))
  await submitDispositionProof({requestKey,dispositionId:lotId,
   proofKind:'DISPOSAL_CERTIFICATE',evidenceReference:'DIS-2026-REAL',
   documentSha256:'a'.repeat(64),explanation:'Internal submitted evidence metadata'})
  await decideDispositionProof(proof.id,{decision:'REVIEW',
   reason:'Internal metadata validated'})
  expect(sent).toHaveLength(2)
  expect(sent.every(x=>x.init.method==='POST')).toBe(true)
  expect(JSON.parse(String(sent[0].init.body))).toMatchObject({
   requestKey,documentSha256:'a'.repeat(64)})
  expect(new Headers(sent[1].init.headers).get('X-CSRF-TOKEN'))
   .toBe('v118-valid-csrf-token')
 })
 it('bad URL identifiers reject before network; 409 never reports completed claim',async()=>{
  const sent=mock(()=>({payload:emptyPage}))
  expect(()=>fetchSupplierClaimsPage('../cross-tenant','ALL')).toThrow()
  expect(()=>fetchLocalPurchasePlansPage('not-a-uuid')).toThrow()
  expect(()=>decideDispositionProof('../bad',{decision:'REVIEW',
   reason:'Internal metadata validated'})).toThrow()
  expect(sent).toHaveLength(0)
  const other=mock(()=>({status:409,payload:{message:'Claim already reviewed'}}))
  await expect(actSupplierClaim(claimId,'approve')).rejects.toThrow()
  expect(other).toHaveLength(1)
 })
 it('refuses malformed page responses, does not claim 200 rows were all suppliers',async()=>{
  mock(()=>({payload:[rule]}))
  await expect(fetchSupplierSkuRulesPage()).rejects.toThrow()
  mock(()=>({payload:{items:[],hasMore:true,nextCursor:null,pageSize:50}}))
  await expect(fetchSupplierClaimsPage(order,'ALL')).rejects.toThrow()
 })
})
