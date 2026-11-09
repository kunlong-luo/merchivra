import {afterEach,describe,expect,it,vi} from 'vitest'
import {
 fetchLotCountCases,fetchLotCountCasesPage,fetchDestinationTransfersPage,fetchLotDispositionsPage,submitLotCount,decideLotCount,
 fetchDestinationTransfers,acknowledgeDestinationTransfer,
 fetchLotDispositions,fetchUnplannedQuarantines,proposeLotDisposition,
 decideLotDisposition,reportLotDispositionCompletion
} from './lot-reconciliation'
afterEach(()=>vi.unstubAllGlobals())
const warehouse='11111111-1111-4111-8111-111111111111'
const caseId='22222222-2222-4222-8222-222222222222'
const actionId='33333333-3333-4333-8333-333333333333'
const position='44444444-4444-4444-8444-444444444444'
const sku='55555555-5555-4555-8555-555555555555'
const bin='66666666-6666-4666-8666-666666666666'
const owner='77777777-7777-4777-8777-777777777777'
const reviewer='88888888-8888-4888-8888-888888888888'
const key='99999999-9999-4999-8999-999999999999'
const dispositionId='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const transferId='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const operation='cccccccc-cccc-4ccc-8ccc-cccccccccccc'
const count=(status:string)=>({
 id:caseId,observationActionId:actionId,positionId:position,
 warehouseId:warehouse,productSkuId:sku,expectedBinId:bin,
 expectedPositionQuantity:10,observedQuantity:7,expectedOnHand:20,
 reason:'Physical cartons were recounted',status,
 submittedBy:owner,reviewedBy:status==='PENDING_REVIEW'?null:reviewer,
 operationId:status==='APPLIED'?operation:null,
 reviewReason:status==='PENDING_REVIEW'?null:'Independent count checked'
})
const transfer=(status:string)=>({
 actionId:transferId,destinationWarehouseId:warehouse,
 sourcePositionId:position,destinationPositionId:bin,
 expectedQuantity:8,receivedQuantity:status==='MISMATCH'?6:0,status,
 transferredBy:owner,acknowledgedBy:status==='POSTED_AWAITING_DESTINATION'?null:reviewer,
 reference:status==='POSTED_AWAITING_DESTINATION'?null:'RCV-1001'
})
const disposition=(status:string)=>({
 id:dispositionId,quarantineActionId:actionId,warehouseId:warehouse,
 quantity:3,method:'DISPOSAL',status,
 reason:'Expired goods were isolated',proposedBy:owner,
 reviewedBy:status==='PENDING_APPROVAL'?null:reviewer,
 completedBy:status==='EXECUTED_REPORTED'?owner:null,
 completionReference:status==='EXECUTED_REPORTED'?'DIS-1001':null
})
function mock(handler:(url:string,method?:string)=>unknown){
 const calls:Array<{url:string;opts?:RequestInit}>=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,opts?:RequestInit)=>{
  calls.push({url,opts})
  if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
   token:'v50-valid-test-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
  return new Response(JSON.stringify(handler(url,opts?.method)),{status:200})
 }))
 return calls
}
describe('V50 local lot reconciliation evidence contracts',()=>{
 it('keeps unreviewed physical count observations separate from signed corrections',async()=>{
  const calls=mock(()=>[count('PENDING_REVIEW')])
  const cases=await fetchLotCountCases(warehouse)
  expect(cases[0].operationId).toBeNull()
  expect(cases[0].status).toBe('PENDING_REVIEW')
  expect(calls[0].opts?.method).toBe('GET')
 })
 it('submits count snapshot for second owner with request idempotency and CSRF',async()=>{
  const calls=mock(()=>count('PENDING_REVIEW'))
  await submitLotCount({requestKey:key,observationActionId:actionId,
   reason:'Physical cartons were recounted'})
  const post=calls.find(c=>c.opts?.method==='POST')
  expect(post?.url).toBe('/api/v1/purchasing/lot-reconciliation/counts')
  expect(new Headers(post?.opts?.headers).get('X-CSRF-TOKEN')).toBe('v50-valid-test-csrf-token')
  expect(JSON.parse(String(post?.opts?.body))).toMatchObject({
   requestKey:key,observationActionId:actionId})
 })
 it('applied shortage is tied to official signed COUNT operation',async()=>{
  const calls=mock(()=>count('APPLIED'))
  const result=await decideLotCount(caseId,{decision:'APPROVE',reason:'Independent count checked'})
  expect(result.status).toBe('APPLIED')
  expect(result.operationId).toBe(operation)
  expect(JSON.parse(String(calls.find(c=>c.opts?.method==='POST')?.opts?.body)))
   .toEqual({decision:'APPROVE',reason:'Independent count checked'})
 })
 it('unknown surplus remains evidence required without any signed stock credit',async()=>{
  mock(()=>({...count('EVIDENCE_REQUIRED'),observedQuantity:12,operationId:null}))
  const result=await decideLotCount(caseId,{decision:'APPROVE',reason:'Independent count checked'})
  expect(result.status).toBe('EVIDENCE_REQUIRED')
  expect(result.operationId).toBeNull()
 })
 it('transfer receiver reports physical mismatch without another transfer POST',async()=>{
  const calls=mock(url=>url.includes('/acknowledge')?transfer('MISMATCH'):[
   transfer('POSTED_AWAITING_DESTINATION')])
  expect((await fetchDestinationTransfers(warehouse))[0].status)
   .toBe('POSTED_AWAITING_DESTINATION')
  const received=await acknowledgeDestinationTransfer(transferId,{
   requestKey:key,receivedQuantity:6,reference:'RCV-1001',
   reason:'Six physical cartons were received'})
  expect(received.status).toBe('MISMATCH')
  const post=calls.find(c=>c.opts?.method==='POST')
  expect(post?.url).toContain('/transfers/'+transferId+'/acknowledge')
  expect(JSON.parse(String(post?.opts?.body)).receivedQuantity).toBe(6)
 })
 it('lists source-bound quarantines which are already deducted from stock',async()=>{
  mock(url=>url.includes('unplanned-quarantines')?[actionId]:[disposition('APPROVED')])
  expect(await fetchUnplannedQuarantines(warehouse)).toEqual([actionId])
  expect((await fetchLotDispositions(warehouse))[0].status).toBe('APPROVED')
 })
 it('disposition plan, independent decision and physical report are separate POSTs',async()=>{
  const calls=mock(url=>url.includes('/completion')?disposition('EXECUTED_REPORTED'):
   url.includes('/decision')?disposition('APPROVED'):disposition('PENDING_APPROVAL'))
  await proposeLotDisposition({requestKey:key,quarantineActionId:actionId,
   method:'DISPOSAL',reason:'Expired goods were isolated'})
  await decideLotDisposition(dispositionId,{decision:'APPROVE',reason:'Physical lot inspected'})
  const result=await reportLotDispositionCompletion(dispositionId,{
   reference:'DIS-1001',reason:'Disposal documented by operator'})
  expect(result.status).toBe('EXECUTED_REPORTED')
  expect(calls.filter(c=>c.opts?.method==='POST')).toHaveLength(3)
  expect(JSON.parse(String(calls.filter(c=>c.opts?.method==='POST')[2].opts?.body)))
   .toEqual({reference:'DIS-1001',reason:'Disposal documented by operator'})
 })
})

describe('V121 scoped reconciliation history pages',()=>{
 it('parses count pages and carries the opaque continuation cursor',async()=>{
  const calls=mock(()=>({items:[count('PENDING_REVIEW')],nextCursor:'opaque_page',
   hasMore:true,pageSize:50}))
  const first=await fetchLotCountCasesPage(warehouse)
  expect(first.items).toHaveLength(1)
  expect(first.hasMore).toBe(true)
  await fetchLotCountCasesPage(warehouse,first.nextCursor)
  expect(calls[1].url).toContain('cursor=opaque_page')
 })
 it('rejects malformed response rather than treating missing evidence as empty',async()=>{
  mock(()=>({items:[],nextCursor:null,hasMore:'unknown',pageSize:50}))
  await expect(fetchDestinationTransfersPage(warehouse)).rejects.toThrow()
 })
 it('keeps destination transfer and disposition pagination read-only',async()=>{
  const calls=mock(url=>({items:url.includes('transfers')?[transfer('MISMATCH')]:
   [disposition('APPROVED')],nextCursor:null,hasMore:false,pageSize:50}))
  expect((await fetchDestinationTransfersPage(warehouse)).items).toHaveLength(1)
  expect((await fetchLotDispositionsPage(warehouse)).items).toHaveLength(1)
  expect(calls.every(c=>c.opts?.method==='GET')).toBe(true)
 })
})
