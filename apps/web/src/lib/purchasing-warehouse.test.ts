import {afterEach,describe,expect,it,vi} from 'vitest'
import {receivePurchase,fetchWarehouseStock,fetchPendingReceipts} from './purchasing'
afterEach(()=>vi.unstubAllGlobals())

const order='11111111-1111-4111-8111-111111111111'
const line='22222222-2222-4222-8222-222222222222'
const warehouse='33333333-3333-4333-8333-333333333333'
const sku='44444444-4444-4444-8444-444444444444'
const actor='55555555-5555-4555-8555-555555555555'

describe('local purchase warehouse contract',()=>{
 it('requires explicitly selected warehouse on the CSRF-protected receipt request',async()=>{
  const seen:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   seen.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'local-csrf',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({
    order:{id:order,orderNumber:'PO-T',supplierId:sku,status:'RECEIVING',
     currency:'USD',createdBy:actor,approvedBy:null,notes:null,
     updatedAt:'2026-10-31T08:00:00Z'},
    lines:[{id:line,productSkuId:sku,orderedQuantity:8,receivedQuantity:3,unitCost:'4.50'}]
   }),{status:200})
  }))
  const result=await receivePurchase(order,line,3,warehouse)
  expect(result.order.status).toBe('RECEIVING')
  const post=seen.find(r=>r.options?.method==='POST')
  expect(post?.url).toBe('/api/v1/purchasing/orders/'+order+'/receive')
  expect(JSON.parse(String(post?.options?.body))).toEqual({lineId:line,quantity:3,warehouseId:warehouse})
  expect(post?.options?.headers).toMatchObject({'X-CSRF-TOKEN':'local-csrf'})
 })
 it('reads tenant-scoped stock separately from approved pending purchases',async()=>{
  const urls:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   urls.push(url)
   if(url.includes('warehouse-stock'))return new Response(JSON.stringify([{
    warehouseId:warehouse,productSkuId:sku,internalSku:'LOCAL-1',
    recordedReceivedQuantity:12,onHandQuantity:12
   }]),{status:200})
   return new Response(JSON.stringify([{
    purchaseOrderId:order,orderNumber:'PO-T',purchaseOrderLineId:line,
    productSkuId:sku,internalSku:'LOCAL-1',status:'APPROVED',
    orderedQuantity:20,receivedQuantity:8,remainingQuantity:12,
    currency:'USD',unitCost:'4.50'
   }]),{status:200})
  }))
  const received=await fetchWarehouseStock(warehouse)
  const pending=await fetchPendingReceipts()
  expect(received[0].recordedReceivedQuantity).toBe(12)
  expect(pending[0].remainingQuantity).toBe(12)
  expect(urls).toEqual(['/api/v1/purchasing/warehouse-stock?warehouseId='+warehouse,
    '/api/v1/purchasing/pending-receipts'])
 })
})
