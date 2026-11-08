import {afterEach,describe,expect,it,vi} from 'vitest'
import {createSupplier,createWarehouse,createPurchaseOrder,
 fetchPurchaseOrder,purchaseAction,receivePurchase,postStockOperation,
 fetchWarehouseLabels} from './purchasing'

const supplierId='11111111-1111-4111-8111-111111111111'
const warehouseId='22222222-2222-4222-8222-222222222222'
const orderId='33333333-3333-4333-8333-333333333333'
const lineId='44444444-4444-4444-8444-444444444444'
const skuId='55555555-5555-4555-8555-555555555555'
const actorId='66666666-6666-4666-8666-666666666666'
const sourceId='77777777-7777-4777-8777-777777777777'
const asnId='88888888-8888-4888-8888-888888888888'
const requestKey='99999999-9999-4999-8999-999999999999'
const supplier={id:supplierId,code:'V115-SUP',name:'V115 Supplier',status:'ACTIVE'}
const warehouse={id:warehouseId,code:'V115-WH',name:'V115 Warehouse',status:'ACTIVE'}
const order={id:orderId,orderNumber:'PO-V115',supplierId,status:'DRAFT',
 currency:'USD',createdBy:actorId,approvedBy:null,notes:null,
 updatedAt:'2026-11-08T12:00:00Z'}
const detail={order,lines:[{id:lineId,productSkuId:skuId,orderedQuantity:10,
 receivedQuantity:0,unitCost:'5.75'}]}
const operation={id:sourceId,type:'TRANSFER',warehouseId,
 destinationWarehouseId:supplierId,productSkuId:skuId,
 requestedQuantity:2,expectedOnHand:7,appliedDelta:-2,afterQuantity:5,
 reason:'V115 verified movement',createdBy:actorId,createdAt:'2026-11-08T12:00:00Z'}
type RequestRecord={url:string;method:string;headers:Headers;body?:string}
const seen:RequestRecord[]=[]
function stubResponse(data:unknown,status=200){
 seen.length=0
 vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
  const method=options?.method??'GET'
  if(url.endsWith('/auth/csrf'))
   return new Response(JSON.stringify({token:'v115-csrf-valid',headerName:'X-CSRF-TOKEN'}),
    {status:200,headers:{'Content-Type':'application/json'}})
  seen.push({url,method,headers:new Headers(options?.headers),
   body:options?.body===undefined?undefined:String(options.body)})
  return new Response(JSON.stringify(data),{
   status,headers:{'Content-Type':'application/json'}})
 }))
}
afterEach(()=>{vi.unstubAllGlobals();seen.length=0})
function assertMutation(path:string,body?:unknown){
 const post=seen.at(-1)!
 expect(post.method).toBe('POST')
 expect(post.url).toBe(path)
 expect(post.headers.get('X-CSRF-TOKEN')).toBe('v115-csrf-valid')
 expect(post.headers.get('Content-Type')).toBe(body===undefined?null:'application/json')
 if(body!==undefined)expect(JSON.parse(post.body!)).toEqual(body)
}

describe('V115 generated purchasing write functions with Spring Session CSRF',()=>{
 it('creates warehouses and suppliers as POSTs, never tenant-injected URLs',async()=>{
  stubResponse(warehouse,201)
  expect((await createWarehouse('V115-WH','V115 Warehouse')).id).toBe(warehouseId)
  assertMutation('/api/v1/purchasing/warehouses',{
   warehouseCode:'V115-WH',displayName:'V115 Warehouse'})
  stubResponse(supplier,201)
  expect((await createSupplier('V115-SUP','V115 Supplier')).status).toBe('ACTIVE')
  assertMutation('/api/v1/purchasing/suppliers',{
   supplierCode:'V115-SUP',displayName:'V115 Supplier'})
 })
 it('creates a draft with original-decimal unit cost strings and all line IDs',async()=>{
  stubResponse(detail,201)
  const res=await createPurchaseOrder(supplierId,'USD','V115 notes',
   [{productSkuId:skuId,quantity:10,unitCost:'5.75'}])
  expect(res.order.id).toBe(orderId)
  assertMutation('/api/v1/purchasing/orders',{
   supplierId,currency:'USD',notes:'V115 notes',
   lines:[{productSkuId:skuId,quantity:10,unitCost:'5.75'}]})
 })
 it('fetches a full detail with GET but submits/approves/cancels with POST and CSRF',async()=>{
  stubResponse(detail)
  expect((await fetchPurchaseOrder(orderId)).lines[0].unitCost).toBe('5.75')
  expect(seen[0].url).toBe('/api/v1/purchasing/orders/'+orderId)
  expect(seen[0].method).toBe('GET')
  for(const action of ['submit','approve','cancel'] as const){
   stubResponse(detail)
   await purchaseAction(orderId,action)
   assertMutation('/api/v1/purchasing/orders/'+orderId+'/'+action)
  }
 })
 it('receives only with explicitly selected warehouse and optional verified ASN',async()=>{
  stubResponse(detail)
  await receivePurchase(orderId,lineId,3,warehouseId,asnId)
  assertMutation('/api/v1/purchasing/orders/'+orderId+'/receive',{
   lineId,quantity:3,warehouseId,asnId})
  stubResponse(detail)
  await receivePurchase(orderId,lineId,1,warehouseId,null)
  assertMutation('/api/v1/purchasing/orders/'+orderId+'/receive',{
   lineId,quantity:1,warehouseId})
 })
 it('preserves signed idempotent inventory operation and expected balance',async()=>{
  stubResponse(operation,201)
  const body={requestKey,type:'TRANSFER' as const,warehouseId,
   destinationWarehouseId:supplierId,productSkuId:skuId,quantity:2,
   expectedOnHand:7,reason:'V115 verified movement'}
  const result=await postStockOperation(body)
  expect(result.appliedDelta).toBe(-2)
  assertMutation('/api/v1/purchasing/warehouse-operations',body)
 })
 it('rejects server conflicts rather than treating a failed write as success',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   if(url.endsWith('/auth/csrf'))
    return new Response(JSON.stringify({token:'v115-csrf-valid',headerName:'X-CSRF-TOKEN'}))
   return new Response(JSON.stringify({message:'Inventory changed'}),{status:409})
  }))
  await expect(postStockOperation({requestKey,type:'TRANSFER',
   warehouseId,destinationWarehouseId:supplierId,productSkuId:skuId,
   quantity:2,expectedOnHand:7,reason:'V115 verified movement'}))
   .rejects.toThrow()
 })
 it('resolves old warehouse labels in a bounded, single no-store GET batch',async()=>{
  stubResponse([warehouse])
  const missingId='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  const result=await fetchWarehouseLabels([warehouseId,missingId])
  expect(result).toEqual([warehouse])
  expect(seen).toHaveLength(1)
  expect(seen[0].method).toBe('GET')
  expect(seen[0].url.startsWith('/api/v1/purchasing/warehouses/resolve?')).toBe(true)
  const url=new URL(seen[0].url,'http://localhost')
  const provided=url.searchParams.getAll('ids').flatMap(part=>part.split(','))
  expect(provided).toEqual([warehouseId,missingId])
  await expect(fetchWarehouseLabels([])).rejects.toThrow()
  await expect(fetchWarehouseLabels([warehouseId,warehouseId])).rejects.toThrow()
 })
})
