import {afterEach,describe,expect,it,vi} from 'vitest'
import {getPurchaseReconciliation,reconciliationSchema} from './fba-purchase-reconciliation'
afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const shipment='22222222-2222-4222-8222-222222222222'
const line='33333333-3333-4333-8333-333333333333'
const row={
 shipmentId:shipment,shipmentReference:'LOCAL-V76',shipmentState:'DRAFT',
 shipmentOrigin:'MOCK',inboundLineId:line,sellerSku:'V76-SKU',
 plannedQuantity:12,locallyReportedQuantity:0,localVarianceDecision:null,
 linkId:null,linkState:null,proofReference:null,
 purchaseOrderId:null,purchaseOrderNumber:null,purchaseOrderState:null,
 purchaseOrderedQuantity:null,purchaseLocalReceivedQuantity:null,
 asnId:null,asnState:null,asnReportedQuantity:null,asnLocalReceivedQuantity:null,
 findingCode:'UNLINKED_DRAFT',severity:'INFO',recommendedAction:'OPTIONAL_MANUAL_LINK'
}
const snapshot={
 truncated:false,shipmentScope:1,totals:{
  examinedLines:1,high:0,warning:0,informational:1,
  pendingIdentity:0,unlinked:1,localVariance:0
 },lines:[row],amazonVerified:false,fbaStockCredited:false
}
describe('V76 read-only local discrepancy board',()=>{
 it('keeps unlinked PO / ASN as null rather than invented zero or Amazon proof',()=>{
  const value=reconciliationSchema.parse(snapshot)
  expect(value.lines[0].purchaseLocalReceivedQuantity).toBeNull()
  expect(value.lines[0].asnReportedQuantity).toBeNull()
  expect(value.amazonVerified).toBe(false)
  expect(reconciliationSchema.safeParse({...snapshot,amazonVerified:true}).success)
   .toBe(false)
 })
 it('rejects impossible local quantities, fake severities and future carrier evidence',()=>{
  expect(reconciliationSchema.safeParse({...snapshot,
   lines:[{...row,locallyReportedQuantity:-1}]}).success).toBe(false)
  expect(reconciliationSchema.safeParse({...snapshot,
   lines:[{...row,findingCode:'AMAZON_DELIVERED'}]}).success).toBe(false)
  expect(reconciliationSchema.safeParse({...snapshot,
   lines:[{...row,severity:'AUTOPAY'}]}).success).toBe(false)
 })
 it('reads only scoped Session endpoint without write, CSRF or tenant params',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   return new Response(JSON.stringify(snapshot),{status:200,
    headers:{'Content-Type':'application/json'}})
  }))
  const result=await getPurchaseReconciliation(mp)
  expect(result.totals.unlinked).toBe(1)
  expect(requests[0].url).toBe(
   '/api/v1/fba/marketplaces/'+mp+'/purchase-reconciliation')
  expect(requests[0].url).not.toContain('tenantId')
  expect(requests[0].options).toMatchObject({method:'GET',
   credentials:'same-origin',cache:'no-store'})
  expect(requests[0].options?.body).toBeUndefined()
 })
 it('fails closed on restricted marketplace',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:404})))
  await expect(getPurchaseReconciliation(mp)).rejects.toThrow('Marketplace')
 })
 it('requires explicit bounded page, not global silently aggregated counts',()=>{
  expect(reconciliationSchema.safeParse({...snapshot,truncated:true}).success).toBe(true)
  expect(reconciliationSchema.safeParse({...snapshot,lines:Array(501).fill(row)}).success)
   .toBe(false)
 })
})
