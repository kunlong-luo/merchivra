import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchPurchaseDeliveryFullSummary,fetchPurchaseDeliveryLinesPage} from './purchase-delivery'

const uuid=(n:number)=>'00000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')
const line=(n:number)=>({
 orderId:uuid(n),orderNumber:'LOCAL-PO-'+n,orderStatus:'APPROVED',
 supplierId:uuid(400),supplierName:'Tenant supplier',currency:'USD',
 lineId:uuid(n+1000),productSkuId:uuid(n+2000),internalSku:'SKU-'+n,
 orderedQuantity:10,receivedQuantity:2,remainingQuantity:8,
 draftAsnQuantity:0,shippedAsnQuantity:0,reportedUnreceivedQuantity:0,
 unlinkedReceiptQuantity:0,awaitingQcReleaseQuantity:2,
 releasedQcAcceptedQuantity:0,qcUnknownQuantity:0,unresolvedVarianceCount:0,
 earliestReportedEta:null,latestReportedEta:null,replenishmentSellerSku:null,
 replenishmentPricingSource:null,deliveryState:'NO_ASN_RECORDED'
})
const totals={
 lineCount:325,pendingQuantity:2600,reportedUnreceivedQuantity:14,
 awaitingQcReleaseQuantity:650,warningLines:325,overdueLines:7,
 unresolvedVarianceLines:13
}
afterEach(()=>vi.unstubAllGlobals())

describe('V119 full warehouse delivery overview and 325-line paging',()=>{
 it('never labels a 50-row page as the complete tenant summary',async()=>{
  const seen:Array<{url:URL;init:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(raw:string,init:RequestInit={})=>{
   const url=new URL(raw,'http://localhost')
   seen.push({url,init})
   if(url.pathname.endsWith('/summary'))
    return new Response(JSON.stringify({asOfDateUtc:'2026-11-09',totals}),{status:200})
   const page=Number(url.searchParams.get('cursor')?.slice(1)??'0')
   const start=page*50
   return new Response(JSON.stringify({items:Array.from({
    length:Math.min(50,325-start)},(_,i)=>line(start+i+1)),
    pageSize:50,nextCursor:start+50<325?'P'+(page+1):null,
    hasMore:start+50<325}),{status:200})
  }))
  const sum=await fetchPurchaseDeliveryFullSummary()
  expect(sum.totals.lineCount).toBe(325)
  expect(sum.totals.pendingQuantity).toBe(2600)
  const cursors:string[]=[]
  let cursor:string|null=null
  const sizes:number[]=[]
  const ids=new Set<string>()
  for(let i=0;i<7;i++){
   const p=await fetchPurchaseDeliveryLinesPage(cursor)
   sizes.push(p.items.length)
   p.items.forEach(x=>ids.add(x.lineId))
   cursors.push(p.nextCursor??'none')
   expect(p.items[0].deliveryState).toBe('NO_ASN_RECORDED')
   cursor=p.nextCursor
  }
  expect(sizes).toEqual([50,50,50,50,50,50,25])
  expect(ids.size).toBe(325)
  expect(cursor).toBeNull()
  expect(seen).toHaveLength(8)
  expect(seen[0].url.pathname).toBe('/api/v1/purchasing/delivery/summary')
  expect(seen[1].url.pathname).toBe('/api/v1/purchasing/delivery/lines/page')
  expect(seen[7].url.searchParams.get('cursor')).toBe('P6')
  for(const call of seen){
   expect(call.init.credentials).toBe('same-origin')
   expect(call.init.cache).toBe('no-store')
   expect(call.init.method).toBe('GET')
   expect(call.url.searchParams.has('tenantId')).toBe(false)
  }
 })
 it('rejects invalid cursor envelopes and incomplete totals rather than estimating',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:Array.from({length:50},(_,i)=>line(i+1)),
   hasMore:true,nextCursor:null,pageSize:50}),{status:200})))
  await expect(fetchPurchaseDeliveryLinesPage()).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   asOfDateUtc:'2026-11-09',totals:{lineCount:325}}),{status:200})))
  await expect(fetchPurchaseDeliveryFullSummary()).rejects.toThrow()
 })
 it('does not fabricate a delivery summary on unauthorized or failed response',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:403})))
  await expect(fetchPurchaseDeliveryFullSummary()).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:500})))
  await expect(fetchPurchaseDeliveryLinesPage()).rejects.toThrow()
 })
})
