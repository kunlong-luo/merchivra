import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchSupplierSkuRulesPage,fetchLocalPurchaseRecommendationsPage,
 fetchLocalPurchasePlansPage} from './purchase-planning'
import {fetchStockBinsPage,fetchReceiptLotPiecesPage} from './lot-locations'
import {fetchReceiptBatchesPage} from './receipt-batches'

afterEach(()=>vi.unstubAllGlobals())
const tenant='11111111-1111-4111-8111-111111111111'
const warehouse='22222222-2222-4222-8222-222222222222'
const supplier='33333333-3333-4333-8333-333333333333'
const sku='44444444-4444-4444-8444-444444444444'
const receipt='55555555-5555-4555-8555-555555555555'
const bin='66666666-6666-4666-8666-666666666666'
const id='77777777-7777-4777-8777-777777777777'
const rule={supplierId:supplier,productSkuId:sku,minimumOrderQuantity:5,
 packMultiple:6,leadTimeDays:14,currency:'USD',unitCost:'2.50',
 updatedAt:'2026-11-05T00:00:00Z'}
const recommendation={warehouseId:warehouse,supplierId:supplier,
 supplierName:'Local test supplier',productSkuId:sku,internalSku:'SKU-501',
 sourceMovementId:id,policyUpdatedAt:'2026-11-05T00:00:00Z',
 ruleUpdatedAt:'2026-11-05T00:00:00Z',onHandQuantity:3,
 reorderPoint:5,targetStock:20,minimumOrderQuantity:5,
 packMultiple:6,leadTimeDays:14,currency:'USD',unitCost:'2.50',
 state:'LOW_STOCK',suggestedQuantity:17,orderQuantity:18}
const plan={id,warehouseId:warehouse,supplierId:supplier,
 productSkuId:sku,sourceMovementId:id,policyUpdatedAt:'2026-11-05T00:00:00Z',
 ruleUpdatedAt:'2026-11-05T00:00:00Z',onHandQuantity:3,reorderPoint:5,
 targetStock:20,suggestedQuantity:17,minimumOrderQuantity:5,packMultiple:6,
 leadTimeDays:14,orderedQuantity:18,currency:'USD',unitCost:'2.50',
 estimatedArrivalDate:'2026-11-19',status:'PROPOSED',
 purchaseOrderId:null,createdBy:tenant,createdAt:'2026-11-05T00:00:00Z'}
const binItem={id:bin,warehouseId:warehouse,code:'QC-RACK-201',
 purpose:'QC_HOLD',status:'ACTIVE'}
const piece={id,receiptId:receipt,warehouseId:warehouse,productSkuId:sku,
 binId:bin,pieceCode:'ROOT-201',quantity:7}
const batch={receiptId:receipt,warehouseId:warehouse,productSkuId:sku,
 lotCode:'LOT-301',receivedQuantity:1400,status:'PENDING_QC',inspectionId:null,
 acceptedQuantity:0,rejectedQuantity:0,inspectedBy:null,releasedBy:null,
 createdAt:'2026-11-05T00:00:00Z'}
const page=(items:unknown[],hasMore=true)=>({items,nextCursor:hasMore?'opaque-token':null,
 hasMore,pageSize:50})
function fake(payload:unknown){
 const calls:string[]=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,opts:RequestInit)=>{
  calls.push(url)
  expect(opts.credentials).toBe('same-origin')
  expect(opts.cache).toBe('no-store')
  return new Response(JSON.stringify(payload),{status:200})
 }))
 return calls
}
describe('V92 server-side local operation selectors',()=>{
 it('loads supplier rules beyond the old 500-row view with encoded cursor',async()=>{
  const calls=fake(page([rule]))
  expect((await fetchSupplierSkuRulesPage('abc+/=')).items[0].productSkuId).toBe(sku)
  const url=new URL(calls[0],'http://localhost')
  expect(url.pathname).toContain('/planning/supplier-rules/page')
  expect(url.searchParams.get('cursor')).toBe('abc+/=')
  expect(url.searchParams.get('limit')).toBe('50')
  expect(url.searchParams.has('tenantId')).toBe(false)
 })
 it('reads recommendations and actionable plans using warehouse-scoped pages',async()=>{
  let urls=fake(page([recommendation]))
  expect((await fetchLocalPurchaseRecommendationsPage(warehouse,'second-page'))
   .items[0].internalSku).toBe('SKU-501')
  expect(urls[0]).toContain('/warehouses/'+warehouse+'/recommendations/page')
  vi.unstubAllGlobals()
  urls=fake(page([plan],false))
  expect((await fetchLocalPurchasePlansPage(warehouse,'older-page')).items[0]
   .status).toBe('PROPOSED')
  expect(urls[0]).toContain('/warehouses/'+warehouse+'/plans/page')
 })
 it('keeps local lot piece total independent from a 50-piece page',async()=>{
  let urls=fake(page([binItem]))
  expect((await fetchStockBinsPage(warehouse,'bin-cursor')).items[0].code)
   .toBe('QC-RACK-201')
  expect(urls[0]).toContain('/bins/page')
  vi.unstubAllGlobals()
  urls=fake({...page([piece],false),totalQuantity:1400})
  const result=await fetchReceiptLotPiecesPage(receipt,'piece-cursor')
  expect(result.items[0].quantity).toBe(7)
  expect(result.totalQuantity).toBe(1400)
  expect(urls[0]).toContain('/pieces/page')
 })
 it('loads a batch after the old 300-row selection boundary',async()=>{
  const urls=fake(page([batch],false))
  expect((await fetchReceiptBatchesPage(id,'batch-cursor')).items[0].lotCode)
   .toBe('LOT-301')
  expect(urls[0]).toContain('/orders/'+id+'/page')
 })
 it('rejects inconsistent cursors instead of silently dropping actionable rows',async()=>{
  fake({...page([rule]),nextCursor:null})
  await expect(fetchSupplierSkuRulesPage()).rejects.toThrow()
 })
})
