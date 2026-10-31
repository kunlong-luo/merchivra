import {afterEach,describe,expect,it,vi} from 'vitest'
import {
 createSupplierAsn,fetchSupplierAsns,supplierAsnAction,
 updateSupplierAsnEta,scanSupplierAsns,supplierAsnFindingState
} from './supplier-asns'
import {receivePurchase} from './purchasing'
afterEach(()=>vi.unstubAllGlobals())
const orderId='11111111-1111-4111-8111-111111111111'
const lineId='22222222-2222-4222-8222-222222222222'
const warehouseId='33333333-3333-4333-8333-333333333333'
const asnId='44444444-4444-4444-8444-444444444444'
const key='55555555-5555-4555-8555-555555555555'
const findingId='66666666-6666-4666-8666-666666666666'
const scanId='77777777-7777-4777-8777-777777777777'
function example(status:'DRAFT'|'SHIPPED'|'CANCELLED'='DRAFT'){
 return {id:asnId,orderId,lineId,warehouseId,quantity:8,receivedQuantity:3,
  expectedArrivalDate:'2026-11-10',trackingReference:'VENDOR-REF',
  status,dispatchReportedAt:status==='SHIPPED'?'2026-10-31T11:00:00Z':null,
  createdAt:'2026-10-31T10:00:00Z'}
}
describe('V43 supplier ASN contract',()=>{
 it('keeps supplier dispatched quantity separate from warehouse received quantity',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([example('SHIPPED')]),
   {status:200})))
  const list=await fetchSupplierAsns(orderId)
  expect(list[0].quantity).toBe(8)
  expect(list[0].receivedQuantity).toBe(3)
  expect(list[0].status).toBe('SHIPPED')
 })
 it('preserves explicit request key and CSRF for supplier notice and ETA updates',async()=>{
  const calls:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   calls.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'valid-supplier-asn-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify(example(url.endsWith('/ship')?'SHIPPED':'DRAFT')),
    {status:201})
  }))
  const request={requestKey:key,lineId,warehouseId,quantity:8,
    expectedArrivalDate:'2026-11-10',trackingReference:'VENDOR-REF'}
  await createSupplierAsn(orderId,request)
  await supplierAsnAction(orderId,asnId,'ship')
  await updateSupplierAsnEta(orderId,asnId,'2026-11-11','2026-11-10','Supplier delay')
  const posts=calls.filter(c=>c.options?.method==='POST')
  expect(posts).toHaveLength(3)
  expect(posts[0].url).toBe('/api/v1/purchasing/supplier-asns/orders/'+orderId)
  expect(JSON.parse(String(posts[0].options?.body))).toEqual(request)
  expect(posts[0].options?.headers).toMatchObject({
   'X-CSRF-TOKEN':'valid-supplier-asn-csrf-token'})
  expect(JSON.parse(String(posts[2].options?.body))).toEqual({
   expectedArrivalDate:'2026-11-11',expectedPreviousEta:'2026-11-10',
   reason:'Supplier delay'})
 })
 it('only links a receipt to an ASN when the operator supplies its ID',async()=>{
  const calls:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   calls.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'valid-supplier-asn-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({order:{
    id:orderId,orderNumber:'PO-T',supplierId:warehouseId,status:'RECEIVING',
    currency:'USD',createdBy:asnId,approvedBy:null,notes:null,
    updatedAt:'2026-10-31T11:00:00Z'},lines:[]}),{status:200})
  }))
  await receivePurchase(orderId,lineId,2,warehouseId,asnId)
  await receivePurchase(orderId,lineId,1,warehouseId)
  const receipts=calls.filter(c=>c.options?.method==='POST')
  expect(JSON.parse(String(receipts[0].options?.body))).toEqual({
   lineId,quantity:2,warehouseId,asnId})
  expect(JSON.parse(String(receipts[1].options?.body))).toEqual({
   lineId,quantity:1,warehouseId})
 })
 it('scans locally and changes findings only via explicit review actions',async()=>{
  const urls:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   urls.push(url)
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'valid-supplier-asn-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify(url.endsWith('/scan')?
    {id:scanId,triggerType:'MANUAL',matchedCount:2,clearedCount:1}:
    {id:findingId,state:'ACKNOWLEDGED'}),{status:200})
  }))
  expect((await scanSupplierAsns()).matchedCount).toBe(2)
  expect((await supplierAsnFindingState(findingId,'ACKNOWLEDGED')).state)
   .toBe('ACKNOWLEDGED')
  expect(urls).toContain('/api/v1/purchasing/supplier-asns/scan')
  expect(urls).toContain('/api/v1/purchasing/supplier-asns/findings/'+findingId+'/state')
 })
})
