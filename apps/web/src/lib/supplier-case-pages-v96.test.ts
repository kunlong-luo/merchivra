import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchPurchaseVariancesPage,fetchSupplierClaimsPage,
 fetchSupplierClaimSourcesPage,fetchSupplierCaseEventsPage}
 from './supplier-disputes'

afterEach(()=>vi.unstubAllGlobals())
const id='11111111-1111-4111-8111-111111111111'
const order='22222222-2222-4222-8222-222222222222'
const actor='33333333-3333-4333-8333-333333333333'
const variance={id,orderId:order,lineId:id,asnId:null,
 kind:'OVERAGE_UNPOSTED',expectedQuantity:0,observedQuantity:1,
 varianceQuantity:1,reason:'Mock discrepancy evidence',
 state:'OPEN',createdBy:actor,reviewedBy:null,createdAt:'2026-11-07T00:00:00Z'}
const claim={id,orderId:order,supplierId:actor,varianceId:id,inspectionId:null,
 requestedQuantity:1,remedy:'REPLACEMENT',reason:'Mock supplier claim',
 status:'DRAFT',createdBy:actor,approvedBy:null,resolutionNote:null,
 createdAt:'2026-11-07T00:00:00Z'}
const audited={id,eventType:'CLAIM_DRAFTED',actorUserId:actor,
 note:'Mock immutable case event',createdAt:'2026-11-07T00:00:00Z'}
const response=(item:unknown)=>({items:[item],nextCursor:'CURSOR',hasMore:true,pageSize:50})
function serve(value:unknown){
 const urls:string[]=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
  urls.push(url)
  expect(options?.method).toBe('GET')
  expect(options?.credentials).toBe('same-origin')
  expect(options?.cache).toBe('no-store')
  return new Response(JSON.stringify(value),{status:200,headers:{'Content-Type':'application/json'}})
 }))
 return urls
}
describe('V96 full supplier cases and evidence without legacy page caps',()=>{
 it('finds an OPEN variance beyond 200 and preserves scope filter',async()=>{
  const urls=serve(response(variance))
  const page=await fetchPurchaseVariancesPage(order,'ACTIONABLE','after-200')
  expect(page.items[0].state).toBe('OPEN')
  expect(urls[0]).toContain('/orders/'+order+'/variances/page?limit=50&filter=ACTIONABLE&cursor=after-200')
 })
 it('finds a DRAFT claim beyond 200',async()=>{
  const urls=serve(response(claim))
  const page=await fetchSupplierClaimsPage(order,'ALL','after-200')
  expect(page.items[0].status).toBe('DRAFT')
  expect(urls[0]).toContain('/orders/'+order+'/claims/page?limit=50&filter=ALL&cursor=after-200')
 })
 it('uses database-authored eligible QC sources instead of partial client lists',async()=>{
  const urls=serve(response({sourceType:'QC',sourceId:id,maxQuantity:2,
   createdAt:'2026-11-07T00:00:00Z'}))
  const page=await fetchSupplierClaimSourcesPage(order,'next')
  expect(page.items[0].sourceType).toBe('QC')
  expect(urls[0]).toContain('/orders/'+order+'/claim-sources/page?limit=50&cursor=next')
 })
 it('pages immutable claim history past the old 100 events',async()=>{
  const urls=serve(response(audited))
  const page=await fetchSupplierCaseEventsPage(id,'claims','after-100')
  expect(page.items[0].eventType).toBe('CLAIM_DRAFTED')
  expect(urls[0]).toContain('/claims/'+id+'/events/page?limit=50&cursor=after-100')
 })
 it('rejects malformed page envelopes instead of treating them as complete',async()=>{
  serve({...response(variance),hasMore:false})
  await expect(fetchPurchaseVariancesPage(order,'ALL')).rejects.toThrow()
  serve({...response(claim),items:[{...claim,status:'INVALID'}]})
  await expect(fetchSupplierClaimsPage(order,'ACTIONABLE')).rejects.toThrow()
 })
})
