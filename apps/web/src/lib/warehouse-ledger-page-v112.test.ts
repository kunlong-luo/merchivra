import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchWarehouseLedgerPage} from './purchasing'

const warehouse='33333333-3333-4333-8333-333333333333'
const sku='55555555-5555-4555-8555-555555555555'
const movement={id:'66666666-6666-4666-8666-666666666666',
 warehouseId:warehouse,productSkuId:sku,internalSku:'V112-SKU_%',purchaseReceiptId:null,
 operationId:'77777777-7777-4777-8777-777777777777',inspectionId:null,lotActionId:null,
 quantity:-2,movementType:'OUTBOUND',reason:'Local outbound operation',
 createdAt:'2026-11-08T12:00:00Z'}
afterEach(()=>vi.unstubAllGlobals())

describe('V112 full local warehouse ledger contract',()=>{
 it('includes warehouse, remote movement type, literal SKU search, cursor and no-store',async()=>{
  const urls:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   urls.push(url)
   expect(options.credentials).toBe('same-origin')
   expect(options.cache).toBe('no-store')
   return new Response(JSON.stringify({items:[movement],
    hasMore:true,nextCursor:'NEXT',pageSize:50}),{status:200})
  }))
  const result=await fetchWarehouseLedgerPage({warehouseId:warehouse,
   movementType:'OUTBOUND',search:'SKU_%',cursor:'PREV'})
  expect(result.items[0].quantity).toBe(-2)
  expect(result.items[0].operationId).toBe(movement.operationId)
  const url=new URL(urls[0],'http://localhost')
  expect(url.pathname).toBe('/api/v1/purchasing/warehouse-ledger/page')
  expect(url.searchParams.get('warehouseId')).toBe(warehouse)
  expect(url.searchParams.get('movementType')).toBe('OUTBOUND')
  expect(url.searchParams.get('search')).toBe('SKU_%')
  expect(url.searchParams.get('cursor')).toBe('PREV')
  expect(url.searchParams.get('limit')).toBe('50')
 })
 it('rejects old truncated list and incoherent next cursor',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(
   JSON.stringify([movement]),{status:200})))
  await expect(fetchWarehouseLedgerPage({warehouseId:warehouse})).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[movement],hasMore:true,nextCursor:null,pageSize:50}),{status:200})))
  await expect(fetchWarehouseLedgerPage({warehouseId:warehouse})).rejects.toThrow()
 })
 it('accepts terminal page without fabricating a total',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[movement],hasMore:false,nextCursor:null,pageSize:50}),{status:200})))
  expect((await fetchWarehouseLedgerPage({warehouseId:warehouse})).items).toHaveLength(1)
 })
})
