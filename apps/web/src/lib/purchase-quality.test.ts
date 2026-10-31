import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchQualityReceipts,inspectQualityReceipt,fetchQualityReturns,
 createQualityReturn,actQualityReturn,scanQualityFindings,changeQualityFinding} from './purchase-quality'

afterEach(()=>vi.unstubAllGlobals())
const orderId='11111111-1111-4111-8111-111111111111'
const receiptId='22222222-2222-4222-8222-222222222222'
const lineId='33333333-3333-4333-8333-333333333333'
const warehouseId='44444444-4444-4444-8444-444444444444'
const skuId='55555555-5555-4555-8555-555555555555'
const qcId='66666666-6666-4666-8666-666666666666'
const requestKey='77777777-7777-4777-8777-777777777777'
const actor='88888888-8888-4888-8888-888888888888'
const returnId='99999999-9999-4999-8999-999999999999'
const findingId='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const responseInspection={id:qcId,receiptId,warehouseId,productSkuId:skuId,
 inspectedQuantity:10,acceptedQuantity:7,rejectedQuantity:3,
 reason:'Three broken packages',inspectedBy:actor,createdAt:'2026-10-31T12:30:00Z'}
const responseReturn={id:returnId,inspectionId:qcId,supplierId:actor,
 quantity:2,reference:'RMA-9',status:'DRAFT',createdBy:actor,
 createdAt:'2026-10-31T13:00:00Z',dispatchedAt:null}

describe('V44 quality, held stock and supplier return',()=>{
 it('distinguishes historical unallocated receipts from explicit recorded QC',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([{
   id:receiptId,orderId,lineId,warehouseId:null,productSkuId:skuId,
   receivedQuantity:10,receivedAt:'2026-10-31T12:00:00Z',
   inspectionId:null,acceptedQuantity:0,rejectedQuantity:0,heldQuantity:0
  }]),{status:200})))
  const rows=await fetchQualityReceipts(orderId)
  expect(rows[0].warehouseId).toBeNull()
  expect(rows[0].inspectionId).toBeNull()
 })
 it('submits explicit accepted and rejected quantities with CSRF and stable idempotency key',async()=>{
  const calls:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   calls.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'valid-qc-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify(responseInspection),{status:201})
  }))
  const result=await inspectQualityReceipt(receiptId,{requestKey,
   acceptedQuantity:7,rejectedQuantity:3,reason:'Three broken packages'})
  expect(result.inspectedQuantity).toBe(10)
  const post=calls.find(c=>c.options?.method==='POST')
  expect(post?.url).toBe('/api/v1/purchasing/quality/receipts/'+receiptId+'/inspect')
  expect(JSON.parse(String(post?.options?.body))).toEqual({
   requestKey,acceptedQuantity:7,rejectedQuantity:3,reason:'Three broken packages'})
  expect(post?.options?.headers).toMatchObject({'X-CSRF-TOKEN':'valid-qc-csrf-token'})
 })
 it('supplier return is a separate draft and dispatch, not a second stock mutation',async()=>{
  const calls:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   calls.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'valid-qc-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   if(url.endsWith('/returns')&&options?.method==='GET')
    return new Response(JSON.stringify([responseReturn]),{status:200})
   return new Response(JSON.stringify(url.endsWith('/dispatch')?
    {...responseReturn,status:'DISPATCHED',dispatchedAt:'2026-10-31T14:00:00Z'}:
    responseReturn),{status:200})
  }))
  const returns=await fetchQualityReturns(qcId)
  expect(returns[0].status).toBe('DRAFT')
  await createQualityReturn(qcId,{requestKey,quantity:2,reference:'RMA-9'})
  expect((await actQualityReturn(returnId,'dispatch')).status).toBe('DISPATCHED')
  const posts=calls.filter(c=>c.options?.method==='POST')
  expect(posts.map(p=>p.url)).toEqual([
   '/api/v1/purchasing/quality/inspections/'+qcId+'/returns',
   '/api/v1/purchasing/quality/returns/'+returnId+'/dispatch'
  ])
  expect(JSON.parse(String(posts[0].options?.body))).toEqual({
   requestKey,quantity:2,reference:'RMA-9'})
 })
 it('quality findings are read-only until explicit owner scans or review transitions',async()=>{
  const calls:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   calls.push(url)
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'valid-qc-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify(url.endsWith('/scan')?
    {id:qcId,triggerType:'MANUAL',matchedCount:1,clearedCount:0}:
    {id:findingId,state:'ACKNOWLEDGED'}),{status:200})
  }))
  expect((await scanQualityFindings()).matchedCount).toBe(1)
  expect((await changeQualityFinding(findingId,'ACKNOWLEDGED')).state)
   .toBe('ACKNOWLEDGED')
  expect(calls).toContain('/api/v1/purchasing/quality/scan')
  expect(calls).toContain('/api/v1/purchasing/quality/findings/'+findingId+'/state')
 })
})
