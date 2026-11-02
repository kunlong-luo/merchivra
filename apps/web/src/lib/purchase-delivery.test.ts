import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchPurchaseDeliveryOverview,deliveryOverviewSchema} from './purchase-delivery'

afterEach(()=>vi.unstubAllGlobals())

describe('V72 purchase delivery evidence boundary',()=>{
 const base={
  orderId:'11111111-1111-4111-8111-111111111111',
  orderNumber:'PO-SYNTHETIC',orderStatus:'APPROVED',
  supplierId:'22222222-2222-4222-8222-222222222222',
  supplierName:'Mock Supplier',currency:'USD',
  lineId:'33333333-3333-4333-8333-333333333333',
  productSkuId:'44444444-4444-4444-8444-444444444444',
  internalSku:'SKU-A',orderedQuantity:36,receivedQuantity:0,remainingQuantity:36,
  draftAsnQuantity:0,shippedAsnQuantity:0,reportedUnreceivedQuantity:0,
  unlinkedReceiptQuantity:0,awaitingQcReleaseQuantity:0,
  releasedQcAcceptedQuantity:0,qcUnknownQuantity:0,
  unresolvedVarianceCount:0,earliestReportedEta:null,latestReportedEta:null,
  replenishmentSellerSku:null,replenishmentPricingSource:null,
  deliveryState:'NO_ASN_RECORDED'
 }
 it('reads only session-scoped delivery overview, preserving missing ETA null',async()=>{
  const calls:Array<{url:string;init?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,init?:RequestInit)=>{
   calls.push({url,init})
   return new Response(JSON.stringify({asOfDateUtc:'2026-11-02',
    totals:{lineCount:1,pendingQuantity:36,reportedUnreceivedQuantity:0,
     awaitingQcReleaseQuantity:0,warningLines:1,overdueLines:0,unresolvedVarianceLines:0},
    lines:[base]}),{status:200})
  }))
  const result=await fetchPurchaseDeliveryOverview()
  expect(result.lines[0].earliestReportedEta).toBeNull()
  expect(result.lines[0].deliveryState).toBe('NO_ASN_RECORDED')
  expect(calls[0].url).toBe('/api/v1/purchasing/delivery/overview')
  expect(calls[0].init).toMatchObject({method:'GET',credentials:'same-origin',cache:'no-store'})
  expect(calls[0].url).not.toContain('tenantId')
 })
 it('rejects invented verified carrier status and negative receipt quantities',()=>{
  const parent={asOfDateUtc:'2026-11-02',
   totals:{lineCount:1,pendingQuantity:36,reportedUnreceivedQuantity:0,
    awaitingQcReleaseQuantity:0,warningLines:1,overdueLines:0,unresolvedVarianceLines:0},
   lines:[base]}
  expect(deliveryOverviewSchema.safeParse(parent).success).toBe(true)
  expect(deliveryOverviewSchema.safeParse({...parent,
   lines:[{...base,deliveryState:'CARRIER_DELIVERED'}]}).success).toBe(false)
  expect(deliveryOverviewSchema.safeParse({...parent,
   lines:[{...base,receivedQuantity:-1}]}).success).toBe(false)
 })
 it('fails closed on non-owner access without a fallback',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:403})))
  await expect(fetchPurchaseDeliveryOverview()).rejects.toThrow('Owner')
 })
})
