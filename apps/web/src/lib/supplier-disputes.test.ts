import {afterEach,describe,expect,it,vi} from 'vitest'
import {
 fetchPurchaseVariances,reportPurchaseVariance,actPurchaseVariance,
 fetchSupplierClaims,createSupplierClaim,actSupplierClaim,
 fetchSupplierPerformance,fetchSupplierCaseEvents
} from './supplier-disputes'

afterEach(()=>vi.unstubAllGlobals())
const order='11111111-1111-4111-8111-111111111111'
const line='22222222-2222-4222-8222-222222222222'
const supplier='33333333-3333-4333-8333-333333333333'
const reporter='44444444-4444-4444-8444-444444444444'
const reviewer='55555555-5555-4555-8555-555555555555'
const requestKey='66666666-6666-4666-8666-666666666666'
const variance='77777777-7777-4777-8777-777777777777'
const claim='88888888-8888-4888-8888-888888888888'
const qc='99999999-9999-4999-8999-999999999999'
const t='2026-11-01T08:00:00Z'
const observation={id:variance,orderId:order,lineId:line,asnId:null,
 kind:'PO_SHORTAGE',expectedQuantity:10,observedQuantity:7,varianceQuantity:3,
 reason:'Vendor did not deliver all three remaining units',state:'OPEN',
 createdBy:reporter,reviewedBy:null,createdAt:t}
const claimData={id:claim,orderId:order,supplierId:supplier,varianceId:variance,
 inspectionId:null,requestedQuantity:3,remedy:'REPLACEMENT',
 reason:'Replace missing three units',status:'DRAFT',createdBy:reporter,
 approvedBy:null,resolutionNote:null,createdAt:t}

function mocked(result:(url:string,method?:string)=>unknown){
 const calls:Array<{url:string;options?:RequestInit}>=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
  calls.push({url,options})
  if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
   token:'test-dispute-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
  return new Response(JSON.stringify(result(url,options?.method)),{status:200})
 }))
 return calls
}
describe('V45 supplier dispute and claims',()=>{
 it('discrepancy snapshot displays observed amount and does not post inventory',async()=>{
  const calls=mocked(()=>[observation])
  const rows=await fetchPurchaseVariances(order)
  expect(rows[0].expectedQuantity).toBe(10)
  expect(rows[0].observedQuantity).toBe(7)
  expect(rows[0].varianceQuantity).toBe(3)
  expect(calls).toHaveLength(1)
  expect(calls[0].url).toContain('/disputes/orders/'+order+'/variances')
 })
 it('creates source-bound discrepancy only through CSRF-protected human POST',async()=>{
  const calls=mocked(()=>observation)
  await reportPurchaseVariance(order,{requestKey,lineId:line,kind:'PO_SHORTAGE',
   asnId:null,reportedExcessQuantity:0,reason:observation.reason})
  const post=calls.find(c=>c.options?.method==='POST')
  expect(post?.options?.headers).toMatchObject({
   'X-CSRF-TOKEN':'test-dispute-csrf-token'})
  expect(JSON.parse(String(post?.options?.body))).toEqual({
   requestKey,lineId:line,kind:'PO_SHORTAGE',asnId:null,
   reportedExcessQuantity:0,reason:observation.reason
  })
  expect(post?.url).toBe('/api/v1/purchasing/disputes/orders/'+order+'/variances')
 })
 it('separate review and claim draft cannot falsely imply external supplier approval',async()=>{
  const calls=mocked(url=>url.endsWith('/review')?
   {...observation,state:'REVIEWED',reviewedBy:reviewer}:claimData)
  const reviewed=await actPurchaseVariance(variance,'review')
  expect(reviewed.state).toBe('REVIEWED')
  const created=await createSupplierClaim({requestKey,varianceId:variance,
   inspectionId:null,requestedQuantity:3,remedy:'REPLACEMENT',
   reason:'Replace missing three units'})
  expect(created.status).toBe('DRAFT')
  const posts=calls.filter(c=>c.options?.method==='POST')
  expect(posts.map(p=>p.url)).toEqual([
   '/api/v1/purchasing/disputes/variances/'+variance+'/review',
   '/api/v1/purchasing/disputes/claims'
  ])
  expect(JSON.parse(String(posts[1].options?.body))).toMatchObject({
   varianceId:variance,inspectionId:null,requestedQuantity:3
  })
 })
 it('claim approval and manual resolution are different protected actions',async()=>{
  const calls=mocked(url=>url.endsWith('/close')?
   {...claimData,status:'CLOSED',approvedBy:reviewer,
    resolutionNote:'Supplier will issue a credit after external verification'}:
   {...claimData,status:'APPROVED',approvedBy:reviewer})
  await actSupplierClaim(claim,'approve')
  const closed=await actSupplierClaim(claim,'close',
   'Supplier will issue a credit after external verification')
  expect(closed.status).toBe('CLOSED')
  const posts=calls.filter(c=>c.options?.method==='POST')
  expect(posts.map(c=>c.url)).toEqual([
   '/api/v1/purchasing/disputes/claims/'+claim+'/approve',
   '/api/v1/purchasing/disputes/claims/'+claim+'/close'
  ])
  expect(posts[0].options?.body).toBeUndefined()
  expect(JSON.parse(String(posts[1].options?.body))).toEqual({
   note:'Supplier will issue a credit after external verification'
  })
 })
 it('supplier scorecard is scoped and does not invent on-time percentages',async()=>{
  const calls=mocked(()=>[{
   supplierId:supplier,supplierName:'Vendor A',orderCount:2,
   orderedUnits:100,receivedUnits:79,rejectedUnits:3,
   openDiscrepancies:1,openClaims:2,overdueAsns:1
  }])
  const score=await fetchSupplierPerformance()
  expect(score[0].orderedUnits).toBe(100)
  expect(score[0].receivedUnits).toBe(79)
  expect(score[0].overdueAsns).toBe(1)
  expect(calls[0].url).toBe('/api/v1/purchasing/disputes/supplier-performance')
 })
 it('source claim and audit read endpoints stay explicit',async()=>{
  const calls=mocked(url=>url.endsWith('/events')?
   [{id:requestKey,eventType:'CLAIM_DRAFTED',actorUserId:reporter,
     note:'Claim created',createdAt:t}]:[claimData])
  expect((await fetchSupplierClaims(order))[0].status).toBe('DRAFT')
  expect((await fetchSupplierCaseEvents(claim,'claims'))[0].eventType)
   .toBe('CLAIM_DRAFTED')
  expect(calls.map(c=>c.url)).toEqual([
   '/api/v1/purchasing/disputes/orders/'+order+'/claims',
   '/api/v1/purchasing/disputes/claims/'+claim+'/events'
  ])
 })
})
