import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchWarehouseLedger,fetchWarehouseStock,postStockOperation} from './purchasing'
afterEach(()=>vi.unstubAllGlobals())
const warehouse='11111111-1111-4111-8111-111111111111'
const destination='22222222-2222-4222-8222-222222222222'
const sku='33333333-3333-4333-8333-333333333333'
const key='44444444-4444-4444-8444-444444444444'
const actor='55555555-5555-4555-8555-555555555555'
const operation='66666666-6666-4666-8666-666666666666'

describe('warehouse stock mutations',()=>{
 it('sends explicit idempotency key, source balance, destination and CSRF',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'test-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({
    id:operation,type:'TRANSFER',warehouseId:warehouse,
    destinationWarehouseId:destination,productSkuId:sku,
    requestedQuantity:4,expectedOnHand:10,appliedDelta:-4,afterQuantity:6,
    reason:'Verified stock movement',createdBy:actor,createdAt:'2026-10-31T00:00:00Z'
   }),{status:201})
  }))
  const input={requestKey:key,type:'TRANSFER' as const,
    warehouseId:warehouse,destinationWarehouseId:destination,productSkuId:sku,
    quantity:4,expectedOnHand:10,reason:'Verified stock movement'}
  const result=await postStockOperation(input)
  expect(result.afterQuantity).toBe(6)
  const post=requests.find(r=>r.options?.method==='POST')
  expect(post?.url).toBe('/api/v1/purchasing/warehouse-operations')
  expect(JSON.parse(String(post?.options?.body))).toEqual(input)
  expect(post?.options?.headers).toMatchObject({'X-CSRF-TOKEN':'test-csrf-token'})
 })
 it('parses signed movements separately from historical purchase receipts',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   if(url.includes('warehouse-stock'))return new Response(JSON.stringify([{
    warehouseId:warehouse,productSkuId:sku,internalSku:'LOCAL-01',
    recordedReceivedQuantity:10,onHandQuantity:6
   }]),{status:200})
   return new Response(JSON.stringify([{
    id:'77777777-7777-4777-8777-777777777777',
    warehouseId:warehouse,productSkuId:sku,internalSku:'LOCAL-01',
    purchaseReceiptId:null,operationId:operation,quantity:-4,
    movementType:'TRANSFER_OUT',reason:'Verified stock movement',
    createdAt:'2026-10-31T00:00:00Z'
   }]),{status:200})
  }))
  const stock=await fetchWarehouseStock(warehouse)
  const ledger=await fetchWarehouseLedger(warehouse)
  expect(stock[0].recordedReceivedQuantity).toBe(10)
  expect(stock[0].onHandQuantity).toBe(6)
  expect(ledger[0].quantity).toBe(-4)
  expect(ledger[0].purchaseReceiptId).toBeNull()
  expect(ledger[0].operationId).toBe(operation)
 })
})
