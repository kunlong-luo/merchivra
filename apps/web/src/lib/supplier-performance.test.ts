import {afterEach,describe,expect,it,vi} from 'vitest'
import {
 fetchSupplierScorecards,fetchPurchaseFollowups,
 scanPurchaseFollowups,setPurchaseFollowupState
} from './supplier-performance'

afterEach(()=>vi.unstubAllGlobals())
describe('V73 supplier evidence and manual follow-up client',()=>{
 const supplier='11111111-1111-4111-8111-111111111111'
 const finding='22222222-2222-4222-8222-222222222222'
 it('requires an evidence score to stay null for missing samples',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([{
   supplierId:supplier,supplierName:'Fictional Supplier',
   completeAsnSampleCount:0,observedOnTimeAsnCount:0,
   inspectedReceiptSampleCount:0,acceptedQcUnits:0,inspectedQcUnits:0,
   activeVarianceCount:0,observedOnTimePercent:null,
   inspectedAcceptancePercent:null,evidenceScore:null,
   scoringStatus:'INSUFFICIENT_EVIDENCE'
  }]),{status:200})))
  const rows=await fetchSupplierScorecards()
  expect(rows[0].evidenceScore).toBeNull()
  expect(rows[0].observedOnTimePercent).toBeNull()
 })
 it('uses only authenticated same-origin GET for the current tenant',async()=>{
  const urls:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,init?:RequestInit)=>{
   urls.push(url)
   expect(init).toMatchObject({method:'GET',credentials:'same-origin',cache:'no-store'})
   return new Response(JSON.stringify([]),{status:200})
  }))
  expect(await fetchPurchaseFollowups()).toEqual([])
  expect(urls[0]).toBe('/api/v1/purchasing/supplier-performance/followups?filter=ACTIVE')
  expect(urls[0]).not.toContain('tenantId')
 })
 it('retains distinct follow-up states for open/acknowledge/done/cleared',async()=>{
  const order='33333333-3333-4333-8333-333333333333'
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([{
   id:finding,orderId:order,orderNumber:'PO-TEST',lineId:supplier,
   internalSku:'SKU-A',supplierName:'Synthetic Supplier',
   ruleCode:'ASN_COVERAGE_GAP',state:'ACKNOWLEDGED',
   approvedAt:'2026-10-20T00:00:00Z',orderedQuantity:20,
   receivedQuantity:0,activeAsnQuantity:12,uncoveredQuantity:8,
   lastSeenAt:'2026-11-02T01:00:00Z'
  }]),{status:200})))
  const rows=await fetchPurchaseFollowups('ALL')
  expect(rows[0].uncoveredQuantity).toBe(8)
  expect(rows[0].state).toBe('ACKNOWLEDGED')
 })
 it('POST scan uses CSRF and never posts tenant identity',async()=>{
  const calls:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   calls.push({url,options})
   if(url.includes('/auth/csrf'))return new Response(JSON.stringify({
     token:'v73-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({id:finding,triggerType:'MANUAL',
     matchedCount:1,clearedCount:0}),{status:200})
  }))
  const result=await scanPurchaseFollowups()
  expect(result.matchedCount).toBe(1)
  const scan=calls.find(x=>x.url.endsWith('/scan'))
  expect(scan?.options?.headers).toMatchObject({'X-CSRF-TOKEN':'v73-token'})
  expect(String(scan?.options?.body)).not.toContain('tenantId')
 })
 it('POST state change does not allow additional arbitrary fields',async()=>{
  const calls:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   calls.push({url,options})
   if(url.includes('/auth/csrf'))return new Response(JSON.stringify({
    token:'v73-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({id:finding,state:'DONE'}),{status:200})
  }))
  const done=await setPurchaseFollowupState(finding,'DONE')
  expect(done.state).toBe('DONE')
  const change=calls.find(x=>x.url.includes('/followups/')&&x.url.endsWith('/state'))
  expect(JSON.parse(String(change?.options?.body))).toEqual({state:'DONE'})
 })
})
