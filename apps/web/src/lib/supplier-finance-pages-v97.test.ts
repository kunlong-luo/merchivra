import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchSupplierScorecardsPage,fetchPurchaseFollowupsPage}
 from './supplier-performance'
import {listStatementsPage,settlementCandidatesPage,settlementAuditPage}
 from './settlements'

afterEach(()=>vi.unstubAllGlobals())
const id='11111111-1111-4111-8111-111111111111'
const marketplace='22222222-2222-4222-8222-222222222222'
const order='33333333-3333-4333-8333-333333333333'
const user='44444444-4444-4444-8444-444444444444'
const score={
 supplierId:id,supplierName:'V97 Mock Supplier 0225',
 completeAsnSampleCount:0,observedOnTimeAsnCount:0,
 inspectedReceiptSampleCount:0,acceptedQcUnits:0,inspectedQcUnits:0,
 activeVarianceCount:0,observedOnTimePercent:null,
 inspectedAcceptancePercent:null,evidenceScore:null,
 scoringStatus:'INSUFFICIENT_EVIDENCE'
}
const followup={
 id,orderId:order,orderNumber:'V97-LOCAL-PO',lineId:id,
 internalSku:'V97-SKU',supplierName:'V97 Mock Supplier',
 ruleCode:'ASN_COVERAGE_GAP',state:'OPEN',
 approvedAt:'2026-11-01T00:00:00Z',orderedQuantity:20,
 receivedQuantity:1,activeAsnQuantity:0,uncoveredQuantity:19,
 lastSeenAt:'2026-11-07T00:00:00Z'
}
const statement={
 id,reference:'V97STM-125',periodStart:'2026-11-01T00:00:00Z',
 periodEnd:'2026-11-08T00:00:00Z',reportedNet:'100.000000',
 currency:'USD',evidenceOrigin:'MANUAL_UNVERIFIED',
 createdAt:'2026-11-07T00:00:00Z'
}
const candidate={
 id,sourceKey:'V97-LOCAL-FICTION-0165',sourceType:'V97_SYNTHETIC_ONLY',
 amount:'1.000000',currency:'USD',postedAt:'2026-11-07T00:00:00Z',
 matchedReferenceKind:null
}
const audit={action:'EVENT_UNLINKED',eventId:id,actorId:user,
 occurredAt:'2026-11-07T00:00:00Z'}
const envelope=(value:unknown)=>({
 items:[value],nextCursor:'opaque-cursor',hasMore:true,pageSize:50
})
function serve(value:unknown) {
 const urls:string[]=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,init?:RequestInit)=>{
  urls.push(url)
  expect(init).toMatchObject({method:'GET',credentials:'same-origin',cache:'no-store'})
  return new Response(JSON.stringify(value),{status:200})
 }))
 return urls
}
describe('V97 supplier and finance cursor pages',()=>{
 it('finds supplier #225 without setting or trusting a score',async()=>{
  const urls=serve(envelope(score))
  const page=await fetchSupplierScorecardsPage('after-200')
  expect(page.items[0].evidenceScore).toBeNull()
  expect(urls[0]).toBe('/api/v1/purchasing/supplier-performance/scorecards/page?limit=50&cursor=after-200')
 })
 it('presents a later purchase action without trusting first-100 followups',async()=>{
  const urls=serve(envelope(followup))
  const page=await fetchPurchaseFollowupsPage('ACTIVE','after-100')
  expect(page.items[0].state).toBe('OPEN')
  expect(urls[0]).toBe('/api/v1/purchasing/supplier-performance/followups/page?limit=50&filter=ACTIVE&cursor=after-100')
 })
 it('lets the operator select an older manually unverified settlement',async()=>{
  const urls=serve(envelope(statement))
  const page=await listStatementsPage(marketplace,'later-than-50')
  expect(page.items[0].evidenceOrigin).toBe('MANUAL_UNVERIFIED')
  expect(urls[0]).toContain('/marketplaces/'+marketplace+'/settlements/page?limit=50&cursor=later-than-50')
 })
 it('routes late LIVE-only candidates through statement-scoped source pages',async()=>{
  const urls=serve(envelope(candidate))
  const page=await settlementCandidatesPage(marketplace,id,'later-than-100')
  expect(page.items[0].matchedReferenceKind).toBeNull()
  expect(urls[0]).toContain('/settlements/'+id+'/candidates/page?limit=50&cursor=later-than-100')
 })
 it('supports immutable audit history older than the 100th action',async()=>{
  const urls=serve(envelope(audit))
  const page=await settlementAuditPage(marketplace,id,'older-audit')
  expect(page.items[0].action).toBe('EVENT_UNLINKED')
  expect(urls[0]).toContain('/settlements/'+id+'/audit/page?limit=50&cursor=older-audit')
 })
 it('fails closed on impossible pagination envelopes and invalid candidate values',async()=>{
  serve({...envelope(candidate),items:[{...candidate,matchedReferenceKind:42}]})
  await expect(settlementCandidatesPage(marketplace,id)).rejects.toThrow()
  serve({...envelope(score),hasMore:false})
  await expect(fetchSupplierScorecardsPage()).rejects.toThrow()
 })
})
