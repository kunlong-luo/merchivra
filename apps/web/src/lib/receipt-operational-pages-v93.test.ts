import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchReceiptBatchesPage,fetchReceiptBatchSummary,
 fetchWarehouseReceiptBatchesPage,fetchReceiptBatchEventsPage} from './receipt-batches'
import {fetchReceiptSupplyPressurePage} from './lot-locations'

afterEach(()=>vi.unstubAllGlobals())
const order='11111111-1111-4111-8111-111111111111'
const warehouse='22222222-2222-4222-8222-222222222222'
const receipt='33333333-3333-4333-8333-333333333333'
const sku='44444444-4444-4444-8444-444444444444'
const actor='55555555-5555-4555-8555-555555555555'
const batch={receiptId:receipt,warehouseId:warehouse,productSkuId:sku,
 lotCode:'LOT-001',receivedQuantity:10,status:'PENDING_QC',
 inspectionId:null,acceptedQuantity:0,rejectedQuantity:0,
 inspectedBy:null,releasedBy:null,createdAt:'2026-11-05T12:00:00Z'}
const audit={id:order,eventType:'LOT_RELABELED',actorUserId:actor,
 oldLotCode:'LOT-OLD',newLotCode:'LOT-001',quantity:null,
 note:'Updated label',createdAt:'2026-11-05T12:01:00Z'}
const pressure={warehouseId:warehouse,productSkuId:sku,internalSku:'SKU-001',
 usableUnits:0,pendingQcUnits:10,acceptedAwaitingReleaseUnits:0,
 declaredAsnInTransitUnits:0}
const page=<T>(item:T)=>({items:[item],nextCursor:'cursor-next',hasMore:true,pageSize:50})
function serve(body:unknown){
 const calls:string[]=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,opts?:RequestInit)=>{
  calls.push(url)
  expect(opts?.cache).toBe('no-store')
  expect(opts?.credentials).toBe('same-origin')
  return new Response(JSON.stringify(body),{status:200})
 }))
 return calls
}
describe('V93 all receipt operational entrypoints use complete keyset pages',()=>{
 it('can locate a QC release batch after 300 rows for order and warehouse',async()=>{
  const calls=serve(page(batch))
  const a=await fetchReceiptBatchesPage(order,'cursor-current')
  const b=await fetchWarehouseReceiptBatchesPage(warehouse,'cursor-current')
  expect(a.items[0].receivedQuantity).toBe(10)
  expect(b.nextCursor).toBe('cursor-next')
  expect(calls[0]).toContain('/orders/'+order+'/page?limit=50&cursor=cursor-current')
  expect(calls[1]).toContain('/warehouses/'+warehouse+'/page?limit=50&cursor=cursor-current')
 })
 it('whole-order summary is not limited to displayed rows',async()=>{
  const calls=serve({pendingReceivedUnits:860,awaitingReleaseAcceptedUnits:0,
   pendingBatchCount:635,awaitingReleaseBatchCount:0})
  const summary=await fetchReceiptBatchSummary(order)
  expect(summary.pendingBatchCount).toBe(635)
  expect(summary.pendingReceivedUnits).toBe(860)
  expect(calls[0]).toContain('/orders/'+order+'/summary')
 })
 it('audit and supplier pressure page cursors work without stock writes',async()=>{
  const calls=serve(page(audit))
  const events=await fetchReceiptBatchEventsPage(receipt,'audit-cursor')
  expect(events.items[0].eventType).toBe('LOT_RELABELED')
  expect(calls[0]).toContain('/events/page?limit=50&cursor=audit-cursor')
  const calls2=serve(page(pressure))
  const rows=await fetchReceiptSupplyPressurePage(warehouse,'sku-cursor')
  expect(rows.items[0].usableUnits).toBe(0)
  expect(rows.items[0].pendingQcUnits).toBe(10)
  expect(calls2[0]).toContain('/pressure/page?limit=50&cursor=sku-cursor')
 })
 it('rejects invalid page booleans to avoid false workbench completion',async()=>{
  serve({...page(batch),hasMore:false})
  await expect(fetchReceiptBatchesPage(order)).rejects.toThrow()
 })
})
