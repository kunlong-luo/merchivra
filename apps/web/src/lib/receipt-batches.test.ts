import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchReceiptBatches,fetchWarehouseReceiptBatches,relabelReceiptBatch,
 releaseReceiptBatch,fetchReceiptBatchEvents} from './receipt-batches'
afterEach(()=>vi.unstubAllGlobals())
const order='11111111-1111-4111-8111-111111111111'
const receipt='22222222-2222-4222-8222-222222222222'
const warehouse='33333333-3333-4333-8333-333333333333'
const sku='44444444-4444-4444-8444-444444444444'
const qc='55555555-5555-4555-8555-555555555555'
const inspector='66666666-6666-4666-8666-666666666666'
const releaseOwner='77777777-7777-4777-8777-777777777777'
const batch=(status:'PENDING_QC'|'INSPECTED'|'RELEASED')=>({
 receiptId:receipt,warehouseId:warehouse,productSkuId:sku,
 lotCode:'LOT-ABCDEF',receivedQuantity:10,status,
 inspectionId:status==='PENDING_QC'?null:qc,
 acceptedQuantity:status==='PENDING_QC'?0:7,
 rejectedQuantity:status==='PENDING_QC'?0:3,
 inspectedBy:status==='PENDING_QC'?null:inspector,
 releasedBy:status==='RELEASED'?releaseOwner:null,
 createdAt:'2026-11-01T10:00:00Z'
})
describe('V46 new real receipts held until separate QC release',()=>{
 it('lists newly received lot as pending without implying usable units',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([batch('PENDING_QC')]),
   {status:200})))
  const held=await fetchReceiptBatches(order)
  expect(held[0].receivedQuantity).toBe(10)
  expect(held[0].status).toBe('PENDING_QC')
  expect(held[0].inspectionId).toBeNull()
 })
 it('separates reviewed accepted/rejected units from actual release status',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([batch('INSPECTED')]),
   {status:200})))
  const rows=await fetchWarehouseReceiptBatches(warehouse)
  expect(rows[0].acceptedQuantity).toBe(7)
  expect(rows[0].rejectedQuantity).toBe(3)
  expect(rows[0].releasedBy).toBeNull()
 })
 it('lot relabel requires the original code, reason and CSRF',async()=>{
  const calls:Array<{url:string;opts?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,opts?:RequestInit)=>{
   calls.push({url,opts})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'valid-lot-csrf',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({...batch('PENDING_QC'),lotCode:'LOT-NEW'}),
    {status:200})
  }))
  const value=await relabelReceiptBatch(receipt,'LOT-ABCDEF','LOT-NEW',
   'Supplier provided official lot number')
  expect(value.lotCode).toBe('LOT-NEW')
  const post=calls.find(c=>c.opts?.method==='POST')
  expect(post?.url).toBe('/api/v1/purchasing/receipt-batches/'+receipt+'/relabel')
  expect(JSON.parse(String(post?.opts?.body))).toEqual({
   expectedLotCode:'LOT-ABCDEF',newLotCode:'LOT-NEW',
   reason:'Supplier provided official lot number'
  })
  expect(new Headers(post?.opts?.headers).get('X-CSRF-TOKEN'))
   .toBe('valid-lot-csrf')
 })
 it('release is a separate human POST and never silently QC-inspects',async()=>{
  const calls:Array<{url:string;opts?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,opts?:RequestInit)=>{
   calls.push({url,opts})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'valid-lot-csrf',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify(batch('RELEASED')),{status:200})
  }))
  const value=await releaseReceiptBatch(receipt)
  expect(value.status).toBe('RELEASED')
  expect(value.acceptedQuantity).toBe(7)
  const post=calls.find(c=>c.opts?.method==='POST')
  expect(post?.url).toBe('/api/v1/purchasing/receipt-batches/'+receipt+'/release')
  expect(post?.opts?.body).toBeUndefined()
 })
 it('displays immutable received, relabel, inspect and release events',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([{
   id:order,eventType:'QC_RELEASED',actorUserId:releaseOwner,
   oldLotCode:null,newLotCode:null,quantity:7,
   note:'Independent Owner released inspected accepted units',
   createdAt:'2026-11-01T11:00:00Z'
  }]),{status:200})))
  const events=await fetchReceiptBatchEvents(receipt)
  expect(events[0].quantity).toBe(7)
  expect(events[0].eventType).toBe('QC_RELEASED')
 })
})
