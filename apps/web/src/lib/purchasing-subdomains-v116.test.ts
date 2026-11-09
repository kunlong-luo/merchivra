import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchSupplierAsns} from './supplier-asns'
import {fetchQualityReceiptsPage,fetchQualityReturnsPage,
 fetchQualityFindingsPage,inspectQualityReceipt} from './purchase-quality'
import {fetchReceiptBatchesPage,fetchWarehouseReceiptBatchesPage,
 fetchReceiptBatchEventsPage,releaseReceiptBatch} from './receipt-batches'
import {fetchStockBinsPage,fetchReceiptLotPiecesPage,
 fetchReceiptSupplyPressurePage,moveReceiptLot} from './lot-locations'

const order='11111111-1111-4111-8111-111111111111'
const warehouse='22222222-2222-4222-8222-222222222222'
const receipt='33333333-3333-4333-8333-333333333333'
const inspection='44444444-4444-4444-8444-444444444444'
const asnId='55555555-5555-4555-8555-555555555555'
const line='66666666-6666-4666-8666-666666666666'
const sku='77777777-7777-4777-8777-777777777777'
const piece='88888888-8888-4888-8888-888888888888'
const sourceBin='99999999-9999-4999-8999-999999999999'
const targetBin='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const key='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const asn={id:asnId,orderId:order,lineId:line,warehouseId:warehouse,
 quantity:8,receivedQuantity:0,expectedArrivalDate:'2026-11-16',
 trackingReference:null,status:'SHIPPED',dispatchReportedAt:'2026-11-08T10:00:00Z',
 createdAt:'2026-11-08T09:00:00Z'}
const batch={receiptId:receipt,warehouseId:warehouse,productSkuId:sku,
 lotCode:'LOT-REAL',receivedQuantity:10,status:'INSPECTED',inspectionId:inspection,
 acceptedQuantity:7,rejectedQuantity:3,inspectedBy:line,releasedBy:null,
 createdAt:'2026-11-08T09:00:00Z'}
const ins={id:inspection,receiptId:receipt,warehouseId:warehouse,
 productSkuId:sku,inspectedQuantity:10,acceptedQuantity:7,rejectedQuantity:3,
 reason:'Three units damaged',inspectedBy:line,createdAt:'2026-11-08T09:00:00Z'}
afterEach(()=>vi.unstubAllGlobals())

describe('V116 generated ASN/QC/receipt lot/bin contracts',()=>{
 it('all keyset views use server limit/cursor with same-origin no-store and preserve full totals',async()=>{
  const captured:Array<{url:URL;method:string}>=[]
  vi.stubGlobal('fetch',vi.fn(async(raw:string,options:RequestInit)=>{
   expect(options.credentials).toBe('same-origin')
   expect(options.cache).toBe('no-store')
   const url=new URL(raw,'http://localhost')
   captured.push({url,method:options.method??'GET'})
   if(url.pathname.endsWith('/pieces/page'))
    return new Response(JSON.stringify({items:[],nextCursor:null,hasMore:false,
     pageSize:50,totalQuantity:216}),{status:200})
   return new Response(JSON.stringify({items:[],nextCursor:null,hasMore:false,
    pageSize:50}),{status:200})
  }))
  const qs=[
   await fetchQualityReceiptsPage(order,'QCUR'),
   await fetchQualityReturnsPage(inspection,'RCUR'),
   await fetchQualityFindingsPage('ALL','FCUR'),
   await fetchReceiptBatchesPage(order,'BCUR'),
   await fetchWarehouseReceiptBatchesPage(warehouse,'WCUR'),
   await fetchReceiptBatchEventsPage(receipt,'ECUR'),
   await fetchStockBinsPage(warehouse,'BINCUR'),
   await fetchReceiptLotPiecesPage(receipt,'PCUR'),
   await fetchReceiptSupplyPressurePage(warehouse,'SCUR')
  ]
  expect(captured).toHaveLength(9)
  expect(qs.every(p=>p.pageSize===50&&p.hasMore===false)).toBe(true)
  expect(qs.every(p=>p.items.length===0)).toBe(true)
  expect((qs[7] as typeof qs[7] & {totalQuantity:number}).totalQuantity).toBe(216)
  expect(captured.map(c=>c.url.searchParams.get('cursor'))).toEqual([
   'QCUR','RCUR','FCUR','BCUR','WCUR','ECUR','BINCUR','PCUR','SCUR'])
  for(const c of captured){
   expect(c.method).toBe('GET')
   expect(c.url.searchParams.get('limit')).toBe('50')
  }
  expect(captured[2].url.searchParams.get('filter')).toBe('ALL')
 })
 it('supplier declared shipment is not receipt or carrier confirmation',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([asn]),{status:200})))
  const found=await fetchSupplierAsns(order)
  expect(found[0].status).toBe('SHIPPED')
  expect(found[0].quantity).toBe(8)
  expect(found[0].receivedQuantity).toBe(0)
 })
 it('rejects malformed route IDs before generated URL interpolation or network calls',()=>{
  const spy=vi.fn()
  vi.stubGlobal('fetch',spy)
  expect(()=>fetchSupplierAsns('../other-tenant')).toThrow()
  expect(()=>fetchQualityReceiptsPage('%2F')).toThrow()
  expect(()=>releaseReceiptBatch('not-a-uuid')).toThrow()
  expect(()=>fetchStockBinsPage('..')).toThrow()
  expect(spy).not.toHaveBeenCalled()
 })
 it('preserves audit-safe CSRF, requestKey and original expected bin in local moves',async()=>{
  const requests:Array<{url:string;options:RequestInit}>=[]
  const pieceRows=[{id:piece,receiptId:receipt,warehouseId:warehouse,
   productSkuId:sku,binId:targetBin,pieceCode:'LOT-A',quantity:10}]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit={})=>{
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'v116-real-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   requests.push({url,options})
   return new Response(JSON.stringify(pieceRows),{status:200})
  }))
  const action={requestKey:key,pieceId:piece,expectedBinId:sourceBin,
   destinationBinId:targetBin,reason:'Physically moved held cartons'}
  expect((await moveReceiptLot(receipt,action))[0].binId).toBe(targetBin)
  const post=requests.at(-1)!
  expect(post.options.method).toBe('POST')
  expect(post.url).toBe('/api/v1/purchasing/lot-locations/receipts/'+receipt+'/move')
  expect(JSON.parse(String(post.options.body))).toEqual(action)
  expect(new Headers(post.options.headers).get('X-CSRF-TOKEN'))
   .toBe('v116-real-csrf-token')
 })
 it('409 fail-closed on QC inspection; never pretend stock was released',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'v116-real-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({message:'QC changed'}),{status:409})
  }))
  await expect(inspectQualityReceipt(receipt,{requestKey:key,
   acceptedQuantity:7,rejectedQuantity:3,reason:'Damaged units hold'}))
   .rejects.toThrow()
 })
 it('rejects incompatible paged DTO contracts rather than treating truncated arrays as all rows',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([batch]),{status:200})))
  await expect(fetchReceiptBatchesPage(order)).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[],pageSize:50,nextCursor:null,hasMore:true}),{status:200})))
  await expect(fetchQualityReceiptsPage(order)).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify(ins),{status:200})))
  await expect(fetchStockBinsPage(warehouse)).rejects.toThrow()
 })
})
