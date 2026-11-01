import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchLotExceptions,proposeLotException,decideLotException} from './lot-exceptions'
afterEach(()=>vi.unstubAllGlobals())
const wh='11111111-1111-4111-8111-111111111111'
const id='22222222-2222-4222-8222-222222222222'
const source='33333333-3333-4333-8333-333333333333'
const receipt='44444444-4444-4444-8444-444444444444'
const sku='55555555-5555-4555-8555-555555555555'
const bin='66666666-6666-4666-8666-666666666666'
const actor='77777777-7777-4777-8777-777777777777'
const reviewer='88888888-8888-4888-8888-888888888888'
const key='99999999-9999-4999-8999-999999999999'
const op='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const result=(type:'RELEASED_SURPLUS_BIND'|'TRANSFER_SHORTAGE',
 status:'PENDING_REVIEW'|'APPLIED'='PENDING_REVIEW')=>({
 id,resolutionType:type,countReviewId:type==='RELEASED_SURPLUS_BIND'?source:null,
 transferActionId:type==='TRANSFER_SHORTAGE'?source:null,
 sourceReceiptId:type==='RELEASED_SURPLUS_BIND'?receipt:null,
 positionId:source,warehouseId:wh,productSkuId:sku,expectedBinId:bin,
 expectedPositionQuantity:10,expectedOnHand:20,adjustmentQuantity:3,
 evidenceReference:'RCPT-2026-001',
 reason:'Physical count and signed receipt reviewed',status,proposedBy:actor,
 reviewedBy:status==='APPLIED'?reviewer:null,
 reviewReason:status==='APPLIED'?'Independent evidence verified':null,
 operationId:status==='APPLIED'&&type==='TRANSFER_SHORTAGE'?op:null
})
function fake(handler:(url:string,method:string)=>unknown){
 const calls:Array<{url:string;options:RequestInit}>=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit={})=>{
  calls.push({url,options})
  if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
   token:'valid-v51-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
  return new Response(JSON.stringify(handler(url,options.method??'GET')),{status:200})
 }))
 return calls
}
describe('V51 independently reviewed inventory exceptions',()=>{
 it('views existing exception evidence without posting warehouse stock',async()=>{
  const calls=fake(()=>[result('RELEASED_SURPLUS_BIND')])
  const data=await fetchLotExceptions(wh)
  expect(data[0].status).toBe('PENDING_REVIEW')
  expect(data[0].operationId).toBeNull()
  expect(calls[0].url).toContain('/warehouses/'+wh)
 })
 it('submits surplus only with explicit real released receipt and idempotency key',async()=>{
  const calls=fake(()=>result('RELEASED_SURPLUS_BIND'))
  await proposeLotException({requestKey:key,resolutionType:'RELEASED_SURPLUS_BIND',
   sourceId:source,sourceReceiptId:receipt,evidenceReference:'RCPT-2026-001',
   reason:'Physical count and signed receipt reviewed'})
  const post=calls.find(x=>x.options.method==='POST')
  expect(post?.options.headers).toMatchObject({'X-CSRF-TOKEN':'valid-v51-csrf-token'})
  expect(JSON.parse(String(post?.options.body))).toMatchObject({
   sourceReceiptId:receipt,requestKey:key,sourceId:source})
 })
 it('shortage proposal cannot introduce a fictitious receipt',async()=>{
  const calls=fake(()=>result('TRANSFER_SHORTAGE'))
  await proposeLotException({requestKey:key,resolutionType:'TRANSFER_SHORTAGE',
   sourceId:source,sourceReceiptId:null,evidenceReference:'RCPT-2026-001',
   reason:'Physical count and signed receipt reviewed'})
  const post=calls.find(x=>x.options.method==='POST')
  expect(JSON.parse(String(post?.options.body))).toMatchObject({
   resolutionType:'TRANSFER_SHORTAGE',sourceReceiptId:null})
 })
 it('reviewed shortage is linked to existing V40 COUNT operation',async()=>{
  const calls=fake(()=>result('TRANSFER_SHORTAGE','APPLIED'))
  const applied=await decideLotException(id,{decision:'APPROVE',
   reason:'Independent evidence verified'})
  expect(applied.operationId).toBe(op)
  expect(JSON.parse(String(calls.find(x=>x.options.method==='POST')?.options.body)))
   .toEqual({decision:'APPROVE',reason:'Independent evidence verified'})
 })
 it('released surplus binding never claims a new signed inventory operation',async()=>{
  fake(()=>result('RELEASED_SURPLUS_BIND','APPLIED'))
  const applied=await decideLotException(id,{decision:'APPROVE',
   reason:'Independent evidence verified'})
  expect(applied.operationId).toBeNull()
  expect(applied.status).toBe('APPLIED')
 })
})
