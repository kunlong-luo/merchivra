import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchWarehouseStockPage,fetchWarehouseStockItem,
 fetchPendingReceiptsPage} from './purchasing'

afterEach(()=>vi.unstubAllGlobals())
const warehouse='33333333-3333-4333-8333-333333333333'
const sku='44444444-4444-4444-8444-444444444444'
const order='55555555-5555-4555-8555-555555555555'
const line='66666666-6666-4666-8666-666666666666'
const stock={warehouseId:warehouse,productSkuId:sku,internalSku:'V113-SKU-0525',
 recordedReceivedQuantity:3,onHandQuantity:4}
const pending={purchaseOrderId:order,orderNumber:'PO-V113-0525',
 purchaseOrderLineId:line,productSkuId:sku,internalSku:'V113-SKU-0525',
 status:'APPROVED',orderedQuantity:8,receivedQuantity:3,
 remainingQuantity:5,currency:'USD',unitCost:'2.50'}

describe('V113 full warehouse read pages',()=>{
 it('performs server-wide stock search, cursor, exact SKU and no-store',async()=>{
  const seen:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,opts:RequestInit)=>{
   seen.push(url)
   expect(opts.credentials).toBe('same-origin')
   expect(opts.cache).toBe('no-store')
   return new Response(JSON.stringify(url.includes('/page')?
    {items:[stock],hasMore:true,nextCursor:'NEXT',pageSize:50}:stock),{status:200})
  }))
  const p=await fetchWarehouseStockPage({warehouseId:warehouse,
   search:'V113-SKU',cursor:'PREV',limit:50})
  expect(p.items[0].productSkuId).toBe(sku)
  const u=new URL(seen[0],'http://localhost')
  expect(u.pathname).toBe('/api/v1/purchasing/warehouse-stock/page')
  expect(u.searchParams.get('warehouseId')).toBe(warehouse)
  expect(u.searchParams.get('search')).toBe('V113-SKU')
  expect(u.searchParams.get('cursor')).toBe('PREV')
  const exact=await fetchWarehouseStockItem(warehouse,sku)
  expect(exact.onHandQuantity).toBe(4)
  const e=new URL(seen[1],'http://localhost')
  expect(e.pathname).toBe('/api/v1/purchasing/warehouse-stock/item')
  expect(e.searchParams.get('productSkuId')).toBe(sku)
  expect(e.searchParams.get('warehouseId')).toBe(warehouse)
 })
 it('filters pending receipts on the backend not on the first 500 rows',async()=>{
  let url=''
  vi.stubGlobal('fetch',vi.fn(async(u:string)=>{
   url=u
   return new Response(JSON.stringify({items:[pending],hasMore:false,
    nextCursor:null,pageSize:50}),{status:200})
  }))
  const p=await fetchPendingReceiptsPage({status:'APPROVED',search:'PO-V113',cursor:'NEXT'})
  expect(p.items[0].purchaseOrderLineId).toBe(line)
  expect(p.items[0].remainingQuantity).toBe(5)
  const u=new URL(url,'http://localhost')
  expect(u.pathname).toBe('/api/v1/purchasing/pending-receipts/page')
  expect(u.searchParams.get('status')).toBe('APPROVED')
  expect(u.searchParams.get('search')).toBe('PO-V113')
  expect(u.searchParams.get('cursor')).toBe('NEXT')
 })
 it('rejects legacy 500-truncated arrays and impossible cursor contract',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([stock]),{status:200})))
  await expect(fetchWarehouseStockPage({warehouseId:warehouse})).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([pending]),{status:200})))
  await expect(fetchPendingReceiptsPage()).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[pending],hasMore:true,nextCursor:null,pageSize:50}),{status:200})))
  await expect(fetchPendingReceiptsPage()).rejects.toThrow()
 })
 it('does not fabricate a zero balance after an exact failed lookup',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('{}',{status:404})))
  await expect(fetchWarehouseStockItem(warehouse,sku)).rejects.toThrow()
 })
})
